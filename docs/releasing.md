# Releasing

Publishing `dev.thuat:ferry` and `dev.thuat:ferry-work` to Maven Central. Maintainer-only: it needs
credentials nobody else has.

## Credentials

Four of them. All are read from a Gradle property, or from an environment variable of the same name
in SCREAMING_SNAKE_CASE (`gradle/publishing.gradle.kts`), and none has a default:

- `signingInMemoryKey` and `signingInMemoryKeyPassword` hold an ASCII-armored GPG private key whose
  public half is on a keyserver.
- `mavenCentralUsername` and `mavenCentralPassword` hold a Central Portal user token.

Absent, nothing breaks and no `Sign` task is even registered. Building, testing and
`./gradlew publishToMavenLocal` all work with none of them, so a contributor cloning this never
needs a signing key.

## The four steps

Tag the commit, deploy, promote, publish.

```bash
git tag -a v0.1.1 -m "0.1.1" && git push origin v0.1.1
JAVA_HOME=/path/to/jdk-21 ./gradlew publishAllPublicationsToMavenCentralRepository
curl -X POST -H "Authorization: Bearer $(printf '%s:%s' "$PORTAL_USER" "$PORTAL_TOKEN" | base64)" \
  "https://ossrh-staging-api.central.sonatype.com/manual/upload/defaultRepository/dev.thuat"
```

Then Publish the deployment at central.sonatype.com/publishing/deployments. Everything before that
button is reversible; a released version never is.

## Two things to know before running it

- Both modules must go up in one Gradle invocation, so they share a staging repository. Central
  validates `ferry-work`'s dependency on `ferry` within the deployment.
- The promote endpoint is scoped to the whole `dev.thuat` namespace, not to this project. It sweeps
  up anything else sitting unpromoted in that namespace's default staging repository.

## Verifying a release

Deployment state and the artifacts themselves, without waiting on the Portal UI:

```bash
# Deployment state: VALIDATED before publishing, PUBLISHED after
curl -s -X POST -H "Authorization: Bearer $AUTH" \
  "https://central.sonatype.com/api/v1/publisher/status?id=$DEPLOYMENT_ID"

# Live on the CDN, 15 to 30 minutes after publishing
curl -sI https://repo1.maven.org/maven2/dev/thuat/ferry/0.1.0/ferry-0.1.0.pom
```

A published POM can be signature-checked cold, which is the only end-to-end proof the key that signed
the artifacts is the one on the keyserver:

```bash
curl -sO https://repo1.maven.org/maven2/dev/thuat/ferry/0.1.0/ferry-0.1.0.pom \
     -O https://repo1.maven.org/maven2/dev/thuat/ferry/0.1.0/ferry-0.1.0.pom.asc
gpg --verify ferry-0.1.0.pom.asc ferry-0.1.0.pom
```
