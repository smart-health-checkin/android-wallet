# Fixtures

Copies of the conformance fixtures this wallet's tests read, from
[smart-health-checkin/spec](https://github.com/smart-health-checkin/spec/tree/main/fixtures):

- `captures/2026-04-30-mattr-safari-org-iso-mdoc/`: a real Safari capture.
- `dcapi-requests/`: generated Digital Credentials API requests.

When the spec's fixtures change, copy them again:

```sh
cp -r ../spec/fixtures/captures/2026-04-30-mattr-safari-org-iso-mdoc fixtures/captures/
cp -r ../spec/fixtures/dcapi-requests/* fixtures/dcapi-requests/
```
