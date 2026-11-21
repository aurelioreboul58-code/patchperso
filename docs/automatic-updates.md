# Automatic Piko Productivity updates

This personal fork keeps the Piko source code and the productivity changes in
the same Git history. The `Sync and publish Piko Productivity` GitHub Actions
workflow checks Piko every six hours.

When Piko changes, the workflow:

1. reapplies the personal productivity commits on top of the new Piko source;
2. builds a new `.mpp` bundle;
3. publishes it as a GitHub release; and
4. updates `patches-bundle.json`, which Morphe checks for remote sources.

It fails without publishing a new bundle if rebasing or compiling fails. Morphe
will then retain the last working version.

## One-time GitHub setup

1. Keep the repository public: Morphe on the phone must be able to download its
   release and metadata without GitHub credentials.
2. In **Settings -> Actions -> General -> Workflow permissions**, select
   **Read and write permissions** and save.
3. Create a **classic** GitHub personal access token with only the
   `read:packages` permission. GitHub Packages currently requires a classic
   token for this type of dependency.
4. In **Settings -> Secrets and variables -> Actions**, create a repository
   secret named `MORPHE_REGISTRY_TOKEN` and paste that token there. Do not put
   it in source code or in a message.
5. Open **Actions -> Sync and publish Piko Productivity -> Run workflow** once
   to publish the first personal bundle.

After that first successful run, add this repository to Morphe as a **Remote**
source. Its remote source updates are then discovered automatically; patching a
new X version still needs the normal confirmation in Morphe.

## If the workflow stops

Do not remove the current source from Morphe. A failed run does not overwrite
the last published bundle. The Actions log will show whether Piko changed a
file that conflicts with the productivity changes or whether the build itself
needs an adjustment.
