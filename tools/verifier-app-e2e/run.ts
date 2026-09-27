// End-to-end test of verifier-app's browser path on an emulator or device:
// the app opens the bridge page in a Custom Tab, the page runs the check-in
// with a web wallet (the SMART Testing Wallet), and the checked response must
// arrive back in the app over the Custom Tabs message channel.
//
//   bun tools/verifier-app-e2e/run.ts [--apk verifier-app-debug.apk] [--serial emulator-5554] [--port 9477] [small] [large] [repeat] [mixed]
//
// direct: the app's second button, Credential Manager with no browser, answered by
//        the reference Android wallet (v0.4.0 or later: it binds app callers to
//        android:apk-key-hash:, which the app decrypts with).
// small: the app's bundled request, answered as the testing wallet's small patient.
// large: the connectathon's L2 request (anything in USCDI), answered as the
//        testing wallet's large patient.
// repeat: two small check-ins through the browser in a row, in one app process.
// mixed: browser, direct, browser, in one app process (direct needs the reference wallet).
// Needs: adb, Chrome on the device with a network connection, puppeteer-core
// (bun install), and the app signed with the key assetlinks.json lists.
import puppeteer, { type Browser, type Page } from "puppeteer-core";
import { $ } from "bun";

const args = process.argv.slice(2);
const opt = (name: string) => { const i = args.indexOf(name); return i >= 0 ? args.splice(i, 2)[1] : undefined; };
const SERIAL = opt("--serial") ?? "emulator-5554";
const PORT = Number(opt("--port") ?? 9477);
const APK = opt("--apk");
const CASES = args.length ? args : ["direct", "small", "large", "repeat", "mixed"];
/** Cases that run several steps without restarting the app between them. */
const SEQUENCES: Record<string, string[]> = { repeat: ["small", "small"], mixed: ["small", "direct", "small"] };
const PKG = "org.smarthealthit.checkin.verifier";
const REGISTRY = "https://smart-health-checkin.org/connectathon/wallets.json";
const L2_REQUEST = "https://smart-health-checkin.org/connectathon/requests/baseline-4.json";
const ADB = `${process.env.ANDROID_HOME ?? `${process.env.HOME}/Android/Sdk`}/platform-tools/adb`;
const adb = (...a: string[]) => $`${ADB} -s ${SERIAL} ${a}`.quiet().nothrow();
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function tapByDescription(desc: string) {
  await adb("shell", "uiautomator", "dump", "/sdcard/ui.xml");
  const xml = (await adb("shell", "cat", "/sdcard/ui.xml")).stdout.toString();
  const m = xml.match(new RegExp(`content-desc="${desc}"[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`));
  if (!m) throw new Error(`no "${desc}" button on screen`);
  const [x, y] = [(+m[1]! + +m[3]!) >> 1, (+m[2]! + +m[4]!) >> 1];
  await adb("shell", "input", "tap", String(x), String(y));
}

/** Ids of Chrome's open tabs (none if Chrome isn't running yet). */
async function tabIds(): Promise<Set<string>> {
  await adb("forward", `tcp:${PORT}`, "localabstract:chrome_devtools_remote");
  try {
    const list = await (await fetch(`http://localhost:${PORT}/json/list`, { signal: AbortSignal.timeout(3000) })).json() as { id: string }[];
    return new Set(list.map((t) => t.id));
  } catch {
    return new Set();
  }
}
const tabId = (p: Page) => (p.target() as unknown as { _targetId: string })._targetId;

const appLog = async () => (await adb("logcat", "-d", "-s", "SHCVerifier:I", "SHCBrowserCheckin:I")).stdout.toString();

/** Poll `fn` until it returns a value; stop early if the app has already logged a failed result. */
async function waitFor<T>(what: string, ms: number, fn: () => Promise<T | undefined>): Promise<T> {
  for (const end = Date.now() + ms; Date.now() < end; await sleep(500)) {
    const v = await fn().catch(() => undefined);
    if (v) return v;
    const failed = (await appLog()).match(/RESULT path=browser ok=false (.*)/);
    if (failed) throw new Error(`the app reported: ${failed[1]}`);
  }
  throw new Error(`timed out waiting for ${what}`);
}

/** Tap the first on-screen node whose text matches; returns whether it tapped. */
async function tapText(re: RegExp): Promise<boolean> {
  await adb("shell", "uiautomator", "dump", "/sdcard/ui.xml");
  const xml = (await adb("shell", "cat", "/sdcard/ui.xml")).stdout.toString();
  for (const m of xml.matchAll(/text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g)) {
    if (!re.test(m[1]!)) continue;
    await adb("shell", "input", "tap", String((+m[2]! + +m[4]!) >> 1), String((+m[3]! + +m[5]!) >> 1));
    return true;
  }
  return false;
}

/** Start the app in a fresh process, or (fresh=false) keep the running one, which is in front after its last result. */
async function launch(fresh: boolean, extras = "") {
  await adb("logcat", "-c");
  if (!fresh) return sleep(1500);
  await adb("shell", "am", "force-stop", PKG);
  // A fresh task, so no Custom Tab from an earlier run sits on top of the app.
  await adb("shell", `am start -S --activity-clear-task -n ${PKG}/.VerifierActivity ${extras}`);
  await sleep(2500);
}

async function runDirect(fresh = true): Promise<boolean> {
  await launch(fresh);
  const t0 = Date.now();
  await tapByDescription("direct-checkin");
  // The platform's sheet, then the wallet's consent screen.
  for (const end = Date.now() + 120000; Date.now() < end; await sleep(1500)) {
    const log = (await adb("logcat", "-d", "-s", "SHCVerifier:I")).stdout.toString();
    const result = log.match(/RESULT path=direct (.*)/)?.[1];
    if (result) {
      const ok = /ok=true/.test(result);
      console.log(`${ok ? "ok  " : "FAIL"} direct: ${((Date.now() - t0) / 1000).toFixed(1)} s total; app: ${result}`);
      return ok;
    }
    // Share first: the wallet's screen also shows the text "SMART Health Check-in".
    if (!(await tapText(/^Share selected data$/))) await tapText(/^(Agree and continue|Continue)$/);
  }
  console.log("FAIL direct: no result within 120 s");
  return false;
}

async function runCase(name: string): Promise<boolean> {
  const steps = SEQUENCES[name];
  if (!steps) return runStep(name, true);
  // Launch extras stay on the app's intent, so every browser step uses the registry.
  for (const [i, step] of steps.entries()) {
    const ok = await runStep(step, i === 0).catch((e) => { console.log(`FAIL ${name} step ${i + 1} (${step}): ${(e as Error).message}`); return false; });
    if (!ok) return false;
  }
  console.log(`ok   ${name}: ${steps.join(", ")} in one app process`);
  return true;
}

async function runStep(name: string, fresh: boolean): Promise<boolean> {
  if (name === "direct") return runDirect(fresh);
  const request = name === "large" ? JSON.stringify(await (await fetch(L2_REQUEST)).json()) : undefined;
  const extras = `--es registry ${REGISTRY}` + (request ? ` --es request '${request.replace(/'/g, "'\\''")}'` : "");
  await launch(fresh, extras);
  // Tabs already open (an earlier step's wallet tab stays open in Chrome) aren't this step's.
  const earlier = await tabIds();
  const t0 = Date.now();
  await tapByDescription("browser-checkin");

  const browser: Browser = await waitFor("Chrome DevTools", 30000, async () => {
    await adb("forward", `tcp:${PORT}`, "localabstract:chrome_devtools_remote");
    return puppeteer.connect({ browserURL: `http://localhost:${PORT}`, defaultViewport: null });
  });
  try {
    const bridge: Page = await waitFor("the bridge page with the request", 60000, async () => {
      for (const p of await browser.pages()) {
        if (p.url().includes("native-bridge") && (await p.evaluate(() => !document.getElementById("picker")!.hidden))) return p;
      }
    });
    // A real tap (puppeteer sends input events), so the page may open the wallet's tab.
    const button = await waitFor("the SMART Testing Wallet in the picker", 30000, async () =>
      (await bridge.evaluateHandle(() =>
        [...document.getElementById("picker")!.shadowRoot!.querySelectorAll("button")].find((e) => /SMART Testing Wallet/.test(e.innerText)) ?? null)).asElement() ?? undefined);
    await (button as unknown as { click(): Promise<void> }).click();
    const wallet: Page = await waitFor("the wallet tab", 30000, async () =>
      (await browser.pages()).find((p) => !earlier.has(tabId(p)) && p.url().includes("/testing-wallet/")));
    const hasOpener = await wallet.evaluate(() => !!window.opener);
    await wallet.waitForFunction(() => { const s = document.getElementById("share") as HTMLButtonElement | null; return !!s && !s.disabled && !document.getElementById("consent")!.hidden; }, { timeout: 60000 });
    if (name === "large") {
      await wallet.select("#patient", "large");
      await wallet.waitForFunction(() => !(document.getElementById("share") as HTMLButtonElement).disabled, { timeout: 60000 });
    }
    await wallet.$eval("#share", (s) => (s as HTMLButtonElement).click());
    const shared = Date.now();

    const log = await waitFor("the result in the app", 180000, async () => {
      const text = await appLog();
      return /RESULT path=browser ok=true/.test(text) ? text : undefined;
    });
    const result = log.match(/RESULT path=browser (.*)/)![1]!;
    const ok = /ok=true/.test(result);
    const begin = log.match(/result-begin parts=(\d+) chars=(\d+)/);
    console.log(`${ok ? "ok  " : "FAIL"} ${name}: window.opener in the wallet tab=${hasOpener}; ` +
      (begin ? `${begin[2]} chars in ${begin[1]} part(s); ` : "") +
      `${((Date.now() - shared) / 1000).toFixed(1)} s from share to app, ${((Date.now() - t0) / 1000).toFixed(1)} s total; app: ${result}`);
    return ok;
  } finally {
    browser.disconnect();
  }
}

if (APK) {
  const r = await adb("install", "-r", APK);
  if (r.exitCode) throw new Error(`install failed: ${r.stderr}`);
}
let failed = 0;
for (const c of CASES) {
  try { if (!(await runCase(c))) failed++; } catch (e) { failed++; console.log(`FAIL ${c}: ${(e as Error).message}`); }
}
process.exit(failed ? 1 : 0);
