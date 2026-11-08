# customer-service

Spring Boot provider extracted from the
[contract-testing](https://github.com/notKish/contract-testing) monorepo.

It publishes its own **provider contract** (an OpenAPI 3.0 document generated from the application)
to PactFlow, where it is compared against the pacts published by `order-service` — bi-directional
contract testing. The consumer-driven verifier is still here too, so the app is checked both ways.

```text
order-service pact ─┐
                    ├─► PactFlow (compares both) ─► can-i-deploy
customer-service OAS┘
```

## Layout

```text
src/main/java/com/example/customer/
├── CustomerController.java        # endpoints + the OpenAPI annotations the contract is built from
├── CustomerServiceApplication.java
└── OpenApiConfig.java             # document metadata (title, version, server)
src/test/java/com/example/customer/
├── CustomerContractVerificationTest.java   # verifies order-service's pacts against the app
└── OpenApiSpecTest.java                    # writes the OpenAPI document used as provider contract
```

Endpoints: `GET /customers/{id}`, `GET /customers`, `POST /customers`. The `io.swagger.v3.oas`
annotations on `CustomerController` are load-bearing — they are what add the `404`/`400` responses
and the `X-Api-Key`, `X-Request-Id` and `Location` headers the consumer pacts assert.

## Prerequisites

- JDK 21 (the Gradle wrapper brings Gradle 8.14)
- Docker, for the PactFlow CLI image (`pactfoundation/pact-cli`)

## Publish the provider contract

```bash
export PACT_BROKER_BASE_URL=https://<tenant>.pactflow.io
export PACT_BROKER_TOKEN=<Settings -> API Tokens>

./gradlew publishProviderContract
```

That runs `openApiSpecTest` (boots the app, saves `/v3/api-docs` to
`build/openapi/openapi.json`), stages the self-verification evidence, and runs
`pactflow publish-provider-contract` with `--specification oas`.

PactFlow requires self-verification **content**, not just a boolean, so the task always attaches a
results file. By default it is a one-line note saying the contract is self-declared. To attach a real
run:

```bash
PACT_VERIFICATION_RESULTS=/tmp/schemathesis.log \
PACT_VERIFIER=schemathesis \
PACT_VERIFICATION_EXIT_CODE=$? \
./gradlew publishProviderContract
```

The file is staged to `build/openapi/self-verification.txt`, so it can live anywhere on the host; the
CLI reads it inside the container.

The version is the 12-character commit hash (`git rev-parse --short=12 HEAD`); `PACT_VERSION`
overrides it. Publishing refuses to run on a dirty working tree — a version must identify exactly one
commit — so commit before you publish. The branch is the checked-out branch (`PACT_PROVIDER_BRANCH`
overrides it in CI).

## Verify the consumer pacts

```bash
export PACT_BROKER_BASE_URL=... PACT_BROKER_TOKEN=...
./gradlew test
```

Fetches the pacts from the broker, calls this app over real HTTP, and publishes the verification
results back against `pact.provider.version`.

## Local conformance check (optional)

The OpenAPI document is generated from the code, so it cannot drift structurally — but the
annotations can still claim behaviour the app does not have. Compare the two directly:

```bash
npx --yes @redocly/cli@latest lint --extends=minimal build/openapi/openapi.json

./gradlew bootRun &
docker run --rm --network host -v "$PWD/build/openapi:/spec:ro" \
  schemathesis/schemathesis:stable run /spec/openapi.json --url http://localhost:8081
```

Known gaps with the demo fixture: a `multipart/form-data` content type without a boundary returns
500 (ST102), Jackson coerces `{"name": false}` to `"false"` (ST201), and a created customer cannot be
fetched back because the controller only serves its fixed fixture (ST302).

## Deployment check

```bash
alias pact-broker='docker run --rm -e PACT_BROKER_BASE_URL -e PACT_BROKER_TOKEN \
  pactfoundation/pact-cli pact-broker'
VERSION=$(./gradlew -q properties | sed -n 's/^version: //p' | head -1)

pact-broker record-deployment --pacticipant customer-service --version "$VERSION" --environment production
pact-broker can-i-deploy --pacticipant customer-service --version "$VERSION" --to-environment production
```
