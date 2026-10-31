"""After the owner's permission (2026-10-31: publish the app and the site): GitHub main holds a commit that
contains the round-3 work (version 0.12.0), read with git ls-remote (no push here)."""
import subprocess
def git(*a): return subprocess.run(["git", *a], cwd="/home/user/basir-run-", capture_output=True, text=True).stdout.strip()
remote = git("ls-remote", "origin", "refs/heads/main").split()[0]
assert git("merge-base", "--is-ancestor", "51543e6", remote) == "" and subprocess.run(["git", "merge-base", "--is-ancestor", "51543e6", remote], cwd="/home/user/basir-run-").returncode == 0
assert 'versionName = "0.12.0"' in git("show", f"{remote}:app/build.gradle.kts")
print("remote main", remote[:7], "contains 0.12.0"); print("PUBLISHED_OK")
