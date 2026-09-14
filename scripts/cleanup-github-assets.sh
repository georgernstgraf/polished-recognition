#!/usr/bin/env bash
#
# Prune GitHub binary assets so only the newest KEEP remain.
#
#   - Actions workflow artifacts: keep newest KEEP PER artifact name
#   - build-* releases: keep newest KEEP (delete release page AND git tag)
#   - orphan build-* git tags (page already gone): keep newest KEEP
#   - v* release pages: keep newest KEEP (git tags are NEVER deleted)
#
# Requires GH_TOKEN with:
#   contents:write  -> releases + git refs
#   actions:write   -> workflow artifacts
#
# Env:
#   REPO=owner/name   (default: $GITHUB_REPOSITORY)
#   KEEP=7            (default 7)
#   DRY_RUN=1         print intended deletions without performing them
#
set -euo pipefail

KEEP="${KEEP:-7}"
REPO="${REPO:-${GITHUB_REPOSITORY:-}}"
DRY_RUN="${DRY_RUN:-0}"

if [ -z "$REPO" ]; then
  echo "error: REPO not set (env REPO or GITHUB_REPOSITORY required)" >&2
  exit 1
fi
: "${GH_TOKEN:?error: GH_TOKEN required}"

log() { echo "[cleanup] $*"; }
dry() { [ "$DRY_RUN" = "1" ]; }

# --- 1. Actions artifacts: keep newest KEEP per name -----------------------
log "Actions artifacts: keep newest $KEEP per name"
gh api --paginate "repos/$REPO/actions/artifacts?per_page=100" \
  --jq '.artifacts[] | [.name, .created_at, .id] | @tsv' \
| sort -t$'\t' -k1,1 -k2,2r \
| awk -F'\t' -v keep="$KEEP" '{ seen[$1]++; if (seen[$1] > keep) print $3 }' \
| while IFS= read -r id; do
    [ -n "$id" ] || continue
    if dry; then
      log "  [dry-run] delete artifact id=$id"
    else
      log "  delete artifact id=$id"
      gh api --method DELETE "repos/$REPO/actions/artifacts/$id" >/dev/null 2>&1 \
        || log "    (failed id=$id)"
    fi
  done

# --- 2. build-* releases: keep newest KEEP (page + tag) --------------------
log "build-* releases: keep newest $KEEP (page + tag)"
while IFS= read -r tag; do
  [ -n "$tag" ] || continue
  if dry; then
    log "  [dry-run] delete release+tag $tag"
  else
    log "  delete release+tag $tag"
    gh release delete "$tag" --yes --cleanup-tag >/dev/null 2>&1 \
      || log "    (failed $tag)"
  fi
done < <(
  gh release list --limit 1000 --json tagName,createdAt \
    --jq 'sort_by(.createdAt) | reverse | .[] | select(.tagName | startswith("build-")) | .tagName' \
  | tail -n +$((KEEP + 1)) || true
)

# --- 3. orphan build-* git tags: keep newest KEEP numerically --------------
log "build-* git tags: keep newest $KEEP (orphans included)"
while IFS= read -r tag; do
  [ -n "$tag" ] || continue
  if dry; then
    log "  [dry-run] delete tag $tag"
  else
    log "  delete tag $tag"
    gh api --method DELETE "repos/$REPO/git/refs/tags/$tag" >/dev/null 2>&1 \
      || log "    (failed $tag)"
  fi
done < <(
  git ls-remote --tags "https://github.com/$REPO.git" 2>/dev/null \
  | awk '{print $2}' | sed 's|refs/tags/||; s|\^{}||' \
  | grep -E '^build-[0-9]+$' | sort -u \
  | sort -t- -k2,2n | head -n "-$KEEP" || true
)

# --- 4. v* release pages: keep newest KEEP (tags kept!) --------------------
log "v* release pages: keep newest $KEEP (tags kept)"
while IFS= read -r tag; do
  [ -n "$tag" ] || continue
  if dry; then
    log "  [dry-run] delete release page $tag (tag kept)"
  else
    log "  delete release page $tag (tag kept)"
    gh release delete "$tag" --yes >/dev/null 2>&1 \
      || log "    (failed $tag)"
  fi
done < <(
  gh release list --limit 1000 --json tagName,createdAt \
    --jq 'sort_by(.createdAt) | reverse | .[] | select(.tagName | test("^v[0-9]")) | .tagName' \
  | tail -n +$((KEEP + 1)) || true
)

log "Done (KEEP=$KEEP, repo=$REPO)."
