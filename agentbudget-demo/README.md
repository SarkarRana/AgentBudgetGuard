# agentbudget-demo

A runnable Spring Boot app showing `@Budgeted` end to end: a session's spend climbing across a
few requests, then the exceeded call surfacing as HTTP 402. No API key needed -- `FakeLlmClient`
stands in for a real provider SDK so this starts and runs with nothing but the JDK and Maven.

Not published anywhere; it exists to be cloned and run, not depended on.

## Run it

From the repository root:

```bash
cd agentbudget-demo
mvn spring-boot:run
```

(The first run needs the rest of the reactor built once, so its dependencies are in your local
repository: `mvn install` from the repository root, before the command above.)

The app starts on port 8080.

## Try it

Every call goes through `ChatServiceImpl.chat`, annotated `@Budgeted(model = "demo-llm")`. The
session's budget is `$3.00` (`agentbudget.limit` in
[`application.yml`](src/main/resources/application.yml)), and `demo-llm`'s pricing is registered
in [`DemoPricingConfiguration`](src/main/java/io/agentbudget/demo/DemoPricingConfiguration.java)
so that `FakeLlmClient`'s fixed usage per call costs exactly `$2.00`. Two calls fit under the
limit; the third does not.

```bash
curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"sessionId":"alice","prompt":"Summarize Q3 roadmap"}'
# {"sessionId":"alice","reply":"Here's a fake AI-generated reply to: ..."}

curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"sessionId":"alice","prompt":"Draft a follow-up email"}'
# succeeds too -- spend is now $4.00, already past the $3.00 limit, but the check that let
# this call through ran when spend was still $2.00

curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"sessionId":"alice","prompt":"One more please"}'
# {"type":"about:blank","title":"Payment Required","status":402,
#  "detail":"Session 'alice' has spent 4.00 USD against a limit of 3.00 USD",
#  "sessionId":"alice","limit":"3.00 USD","currentSpend":"4.00 USD"}
```

That 402 body -- status, detail, and the three extra fields -- comes free from
`agentbudget-spring-boot-starter`'s `AgentBudgetExceptionHandler`; nothing in this demo maps the
exception itself.

Watch the running total build up at any point with:

```bash
curl -s localhost:8080/api/spend/alice
# {"sessionId":"alice","total":"4.00 USD","limit":"3.00 USD","remaining":"0 USD",
#  "perModel":{"demo-llm":"4.00 USD"}}
```

A different `sessionId` starts a fresh budget -- try `bob` and the first two calls succeed again.

## What each file is for

| File | What it shows |
|---|---|
| [`ChatService`](src/main/java/io/agentbudget/demo/ChatService.java) / [`ChatServiceImpl`](src/main/java/io/agentbudget/demo/ChatServiceImpl.java) | `@Budgeted` + `@SessionId` on a real method, and why it needs an interface -- see the class Javadoc |
| [`DemoPricingConfiguration`](src/main/java/io/agentbudget/demo/DemoPricingConfiguration.java) | custom model pricing registration, on top of the built-in catalog |
| [`ChatController`](src/main/java/io/agentbudget/demo/ChatController.java) | injecting the `BudgetGuard` bean directly for spend introspection |
| [`application.yml`](src/main/resources/application.yml) | the whole operator-facing configuration: one `agentbudget.limit` line |
| [`FakeLlmClient`](src/main/java/io/agentbudget/demo/FakeLlmClient.java) | where a real provider call and its usage would go instead |

See the [root README](../README.md) for the wrapping style, streaming, and everything not shown
here.
