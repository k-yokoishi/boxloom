# Publishing the Python SDK to TestPyPI

The repository includes a manually triggered GitHub Actions workflow that tests, builds, smoke-tests, and publishes the `boxloom` Python SDK to TestPyPI. The API token is stored only as a GitHub Actions secret and must not be committed to the repository.

## One-time setup

1. Create and verify an account at [TestPyPI](https://test.pypi.org/).
2. Create a TestPyPI API token. The first upload may need an account-scoped token because the `boxloom` project does not exist until it is uploaded.
3. In the GitHub repository, open **Settings → Secrets and variables → Actions**.
4. Create a repository secret named `TEST_PYPI_API_TOKEN` and paste the complete token, including its `pypi-` prefix, as the value.

Do not add the token to `mise.toml`, `pyproject.toml`, a tracked `.env` file, workflow YAML, or command history.

After the first successful upload, replace the account-scoped token with a token limited to the TestPyPI `boxloom` project when possible.

## Publish from GitHub Actions

1. Confirm that `sdks/python/pyproject.toml` and `sdks/python/src/boxloom/__init__.py` contain the same version and that it has not already been published to TestPyPI.
2. Commit and push that version to `main`.
3. Open **Actions → Publish Python SDK to TestPyPI → Run workflow**.
4. Select `main` and run the workflow.

The workflow can publish only from `main`. It uses the Python version in `sdks/python/.python-version`, runs the unit tests, builds a wheel and source distribution, installs each artifact in isolation, and then uploads both artifacts.

TestPyPI does not allow a published distribution file to be replaced. Increment the package version before publishing another build.

## Verify the published package

The current SDK has no third-party runtime dependencies, so it can be installed directly from TestPyPI in a clean environment:

```bash
python -m pip install --index-url https://test.pypi.org/simple/ boxloom==0.1.0
```

Then verify the installed package:

```bash
python -c "import boxloom; print(boxloom.__all__)"
```

## Optional local publishing

GitHub Actions is the recommended release path. For a local test, expose the TestPyPI token to the process as `UV_PUBLISH_TOKEN`, then run:

```bash
mise run python-publish-testpypi
```

The task refuses to run without `UV_PUBLISH_TOKEN`; it does not read or persist a token from the repository.
