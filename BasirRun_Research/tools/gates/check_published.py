"""G13 (round 4), after the owner's permission ("وبعد الانتهاء قم بتنزيله على موقع"): GitHub main of the app holds the
local HEAD with version 0.12.1, and GitHub main of the site holds the site's local HEAD, which shows 0.12.1 and links
the latest release. Read with git ls-remote / merge-base only (this check pushes nothing)."""
import subprocess, sys
def git(repo, *a):
    r = subprocess.run(["git", *a], cwd=repo, capture_output=True, text=True); return r.returncode, r.stdout.strip()
for repo, need in (("/home/user/basir-run-", 'versionName = "0.12.1"'), ("/home/user/basir-run-site", "الإصدار 0.12.1")):
    _, remote = git(repo, "ls-remote", "origin", "refs/heads/main"); remote = remote.split()[0] if remote else ""
    _, head = git(repo, "rev-parse", "HEAD")
    if not remote: sys.exit(f"{repo}: no remote main")
    git(repo, "fetch", "-q", "origin", "main")
    rc, _ = git(repo, "merge-base", "--is-ancestor", head, remote)
    if rc != 0: sys.exit(f"{repo}: local HEAD {head[:7]} is not on GitHub main {remote[:7]}")
    _, dirty = git(repo, "status", "--porcelain", "--untracked-files=no")
    if dirty: sys.exit(f"{repo}: uncommitted tracked changes")
    f = "app/build.gradle.kts" if repo.endswith("basir-run-") else "index.html"
    _, content = git(repo, "show", f"{remote}:{f}")
    if need not in content: sys.exit(f"{repo}: {need!r} missing on GitHub")
    if f == "index.html" and "releases/download/latest/basir-run.apk" not in content: sys.exit("site lacks the latest release link")
    print(repo, "GitHub main", remote[:7], "holds local HEAD")
print("PUBLISHED_OK")
