# CHIRP submodule (RadioDroid fork)

RadioDroid embeds CHIRP under `AndroidRadioDroid/app/src/main/python/chirp` as a **Git submodule**.

Upstream is [kk7ds/chirp](https://github.com/kk7ds/chirp). RadioDroid may pin commits that include **Android- or RadioDroid-specific driver fixes** that are not (yet) on upstream. Those commits must live on a **fork you control** so fresh clones can run `git submodule update` successfully.

## Submodule remote

- **URL (in `.gitmodules`):** `https://github.com/jnyer27/chirp.git`
- **Suggested default branch on the fork:** `radiodroid` (or use `master` if you prefer—Git stores the **commit SHA** in the parent repo, not the branch name)

Example commit carried on the fork: **`32d7711e`** — `tidradio_h3_nicfw25` fixes (mmap byte/int, DTCS mapping) and related RadioDroid testing.

## One-time: GitHub fork + push your submodule history

1. On GitHub, **fork** `kk7ds/chirp` to your account (same org/user as RadioDroid, e.g. `jnyer27/chirp`).
2. In the submodule working tree:

   ```bash
   cd AndroidRadioDroid/app/src/main/python/chirp
   git remote rename origin upstream
   git remote add origin https://github.com/jnyer27/chirp.git
   git push -u origin master:radiodroid
   ```

   Adjust branch names if your local default is not `master`. The goal is to push every commit the parent repo might reference (including `32d7711e` and its parents).

3. On GitHub → fork **Settings → General → Default branch**, set **`radiodroid`** (optional but matches `.gitmodules` `branch`).

## Cloning RadioDroid

```bash
git clone --recurse-submodules https://github.com/jnyer27/RadioDroid.git
```

Already cloned without submodules:

```bash
git submodule sync
git submodule update --init --recursive
```

After changing `.gitmodules` URL, always run **`git submodule sync`** once so your local `.git/config` matches.

To pull from **upstream** kk7ds after sync (recommended for maintainers):

```bash
cd AndroidRadioDroid/app/src/main/python/chirp
git remote add upstream https://github.com/kk7ds/chirp.git   # skip if already present
git fetch upstream
```

## Bumping the pinned CHIRP revision

1. Commit inside the submodule, then push to **your fork**:

   ```bash
   cd AndroidRadioDroid/app/src/main/python/chirp
   git push origin radiodroid
   ```

2. In the **RadioDroid** repo root:

   ```bash
   git add AndroidRadioDroid/app/src/main/python/chirp
   git commit -m "Bump CHIRP submodule"
   ```

## Merging upstream CHIRP changes

```bash
cd AndroidRadioDroid/app/src/main/python/chirp
git fetch upstream
git checkout radiodroid
git merge upstream/master   # or rebase; resolve conflicts; test RadioDroid
git push origin radiodroid
```

Then bump the submodule pointer in RadioDroid as above.

## License

CHIRP remains GPLv2+ as in the upstream project; your fork inherits the same license for CHIRP code.
