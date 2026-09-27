'use strict';
const {isDeepStrictEqual} = require('node:util');

function ensure(condition, message) {
  if (!condition) throw new Error(message);
}

function parseVersion(value) {
  const match = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-SNAPSHOT)?$/.exec(value);
  ensure(match, `Unsupported Maven version: ${value}`);
  const [major, minor, patch] = match.slice(1, 4).map(Number);
  ensure([major, minor, patch].every(Number.isSafeInteger), 'Version component exceeds safe integer range');
  return {major, minor, patch, snapshot: Boolean(match[4])};
}

// Locate only /project/version. Preserve formatting and every other POM byte.
function projectVersionRange(xml) {
  const stack = [], versions = [];
  let start;
  for (const match of xml.matchAll(/<!--[\s\S]*?-->|<\?[\s\S]*?\?>|<[^>]+>|[^<]+/g)) {
    const token = match[0];
    if (token.startsWith('<!--') || token.startsWith('<?')) continue;
    if (!token.startsWith('<')) continue;
    ensure(!token.startsWith('<!'), 'POM declarations and entities are unsupported');
    const closing = /^<\/([\w.-]+)\s*>$/.exec(token);
    if (closing) {
      ensure(stack.at(-1) === closing[1], 'Malformed POM XML');
      if (stack.join('/') === 'project/version') versions.push({start, end: match.index});
      stack.pop();
    } else {
      const opening = /^<([\w.-]+)(?:\s[^<>]*?)?\s*\/?>$/.exec(token);
      ensure(opening, 'Unsupported POM XML element');
      if (!token.endsWith('/>')) {
        stack.push(opening[1]);
        if (stack.join('/') === 'project/version') start = match.index + token.length;
      }
    }
  }
  ensure(stack.length === 0 && versions.length === 1, 'POM must contain one explicit project version');
  const range = versions[0];
  const content = xml.slice(range.start, range.end);
  const value = content.trim();
  parseVersion(value);
  return {start: range.start + content.indexOf(value), end: range.start + content.indexOf(value) + value.length, value};
}

function readPomVersion(xml) { return projectVersionRange(xml).value; }

function replacePomVersion(xml, expected, next) {
  const {start, end, value} = projectVersionRange(xml);
  ensure(value === expected, 'POM version changed; refusing to overwrite newer work');
  parseVersion(next);
  return xml.slice(0, start) + next + xml.slice(end);
}

function increment(value, bump) {
  const version = parseVersion(value);
  ensure(['patch', 'minor', 'major'].includes(bump), 'Choose patch, minor, or major');
  version[bump] += 1;
  if (bump === 'major') version.minor = 0;
  if (bump !== 'patch') version.patch = 0;
  const result = `${version.major}.${version.minor}.${version.patch}`;
  parseVersion(result);
  return result;
}

function nextSnapshotVersion(stable) {
  ensure(!parseVersion(stable).snapshot, 'Expected a stable released version');
  return `${increment(stable, 'patch')}-SNAPSHOT`;
}

function nextReleaseVersion(snapshot, latestStable, bump = 'patch') {
  ensure(parseVersion(snapshot).snapshot, 'Prepare releases only from a SNAPSHOT version');
  ensure(['patch', 'minor', 'major'].includes(bump), 'Choose patch, minor, or major');
  if (latestStable) {
    ensure(snapshot === nextSnapshotVersion(latestStable), 'Merge the next-development version PR before preparing another release');
    return increment(latestStable, bump);
  }
  const initial = snapshot.replace(/-SNAPSHOT$/, '');
  return bump === 'patch' ? initial : increment(initial, bump);
}

function compareVersions(left, right) {
  const a = parseVersion(left), b = parseVersion(right);
  return a.major - b.major || a.minor - b.minor || a.patch - b.patch;
}

function latestStableVersion(releases) {
  return releases.filter(r => !r.draft && !r.prerelease && /^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(r.tag_name))
    .map(r => r.tag_name.slice(1)).sort(compareVersions).at(-1) || null;
}

function preparationVersion(current, releases, openPRs, bump) {
  ensure(!openPRs.some(pr => pr.head?.ref.startsWith('release/')), 'A release or next-development PR is already open');
  const version = nextReleaseVersion(current, latestStableVersion(releases), bump);
  ensure(!releases.some(r => r.tag_name === `v${version}`), 'That version already has a release or draft');
  return version;
}

function validateRun(run, repository, expectedRunId) {
  ensure(/^[1-9]\d*$/.test(String(expectedRunId)) && Number.isSafeInteger(Number(expectedRunId)), 'Invalid CI run ID');
  ensure(run.id === Number(expectedRunId) && Number.isSafeInteger(run.run_attempt) && run.run_attempt > 0, 'CI run identity mismatch');
  ensure(run.repository?.full_name === repository && run.head_repository?.full_name === repository, 'CI run belongs to another repository');
  ensure(run.event === 'push' && run.head_branch === 'main' && run.path === '.github/workflows/app-ci.yml', 'Select an Application CI push run on main');
  ensure(run.status === 'completed' && run.conclusion === 'success', 'CI must have completed successfully');
  ensure(/^[a-f0-9]{40}$/.test(run.head_sha), 'Invalid CI source SHA');
}

function validateManifest(manifest, run, pomVersion, imageRepository) {
  ensure(manifest && manifest.schema_version === 1, 'Unsupported image manifest schema');
  ensure(manifest.run_id === run.id && manifest.run_attempt === run.run_attempt && manifest.workflow === 'app-ci.yml', 'Manifest CI run or attempt mismatch');
  ensure(manifest.repository === run.repository.full_name && manifest.source_sha === run.head_sha, 'Manifest source mismatch');
  ensure(manifest.version === pomVersion && !parseVersion(pomVersion).snapshot, 'Release requires the exact final POM version');
  ensure(typeof imageRepository === 'string' && imageRepository.length > 0 &&
    manifest.image?.startsWith(`${imageRepository}@sha256:`) &&
    /^[a-z0-9][a-z0-9.:-]*\/[a-z0-9._/-]+@sha256:[a-f0-9]{64}$/.test(manifest.image), 'Manifest image must pin the configured registry repository by digest');
}

function selectManifestArtifact(artifacts, run) {
  const matches = artifacts.filter(a => a.name === 'image-manifest');
  ensure(matches.length === 1 && !matches[0].expired, 'Exactly one unexpired image-manifest artifact is required');
  const artifact = matches[0];
  ensure(artifact.workflow_run?.id === run.id && artifact.workflow_run.head_sha === run.head_sha &&
    artifact.workflow_run.head_branch === 'main', 'Artifact does not belong to the selected main CI run');
  return artifact.id;
}

function validatePreparedRelease(prs, sha, version, repository, botLogin) {
  const matches = prs.filter(pr => pr.merged_at && pr.merge_commit_sha === sha &&
    pr.base?.ref === 'main' && pr.base.repo?.full_name === repository &&
    pr.head?.repo?.full_name === repository && pr.head.ref === `release/prepare-v${version}` &&
    pr.user?.login === botLogin && pr.body?.includes(`<!-- release-preparation:v${version} -->`));
  ensure(matches.length === 1, 'CI commit must be the merged release-preparation PR created by the configured App');
  return matches[0];
}

function validatePomPromotion(before, after, version) {
  const previous = readPomVersion(before);
  ensure(parseVersion(previous).snapshot && !parseVersion(version).snapshot, 'Release preparation must finalize a SNAPSHOT');
  ensure(replacePomVersion(before, previous, version) === after, 'Preparation may change only the project POM version');
}

function snapshotUpdate(released, current) {
  parseVersion(current);
  return current === released ? nextSnapshotVersion(released) : null;
}

function releaseBody(manifest) {
  return `Release of the existing tested Docker image; no rebuild or deployment.\n\n` +
    `- Image: \`${manifest.image}\`\n- Source: \`${manifest.source_sha}\`\n` +
    `- Maven version: \`${manifest.version}\`\n` +
    `- CI: https://github.com/${manifest.repository}/actions/runs/${manifest.run_id}/attempts/${manifest.run_attempt}\n\n` +
    `The attached image-manifest.json is the deployment handoff.\n`;
}

async function optional(request) {
  try { return (await request()).data; } catch (error) {
    if (error.status === 404) return null;
    throw error;
  }
}

async function publishRelease(github, repo, manifest) {
  const tag = `v${manifest.version}`, body = releaseBody(manifest);
  const reference = await optional(() => github.rest.git.getRef({...repo, ref: `tags/${tag}`}));
  const releases = await github.paginate(github.rest.repos.listReleases, {...repo, per_page: 100});
  const matchesByTag = releases.filter(item => item.tag_name === tag);
  ensure(matchesByTag.length <= 1, 'Conflicting releases use the same tag');
  let release = matchesByTag[0];
  ensure(!reference || (reference.object.type === 'commit' && reference.object.sha === manifest.source_sha), 'Existing release tag conflicts with the selected CI commit');
  ensure(!release || (reference && release.body === body), 'Existing release metadata conflicts; refusing to overwrite');
  if (!reference) await github.rest.git.createRef({...repo, ref: `refs/tags/${tag}`, sha: manifest.source_sha});
  if (!release) release = (await github.rest.repos.createRelease({...repo, tag_name: tag,
    target_commitish: manifest.source_sha, name: tag, body, draft: true, prerelease: false})).data;
  const {data: assets} = await github.rest.repos.listReleaseAssets({...repo, release_id: release.id, per_page: 100});
  const matches = assets.filter(asset => asset.name === 'image-manifest.json');
  ensure(matches.length <= 1, 'Ambiguous release manifest assets');
  if (matches.length) {
    const {data} = await github.rest.repos.getReleaseAsset({...repo, asset_id: matches[0].id, headers: {accept: 'application/octet-stream'}});
    // Octokit may decode a JSON response, or return bytes for an octet-stream download.
    const recorded = data && Object.getPrototypeOf(data) === Object.prototype
      ? data : JSON.parse(Buffer.from(data).toString('utf8'));
    ensure(isDeepStrictEqual(recorded, manifest), 'Existing release manifest conflicts; refusing to overwrite');
  } else {
    ensure(release.draft, 'Published release has no manifest; reconciliation is required');
    const data = Buffer.from(JSON.stringify(manifest, null, 2) + '\n');
    await github.rest.repos.uploadReleaseAsset({...repo, release_id: release.id, name: 'image-manifest.json',
      data, headers: {'content-type': 'application/json', 'content-length': data.length}});
  }
  if (release.draft) release = (await github.rest.repos.updateRelease({...repo, release_id: release.id, draft: false, make_latest: 'true'})).data;
  return release.html_url;
}

module.exports = {readPomVersion, replacePomVersion, parseVersion, nextReleaseVersion, nextSnapshotVersion,
  latestStableVersion, compareVersions, preparationVersion, validateRun, validateManifest,
  selectManifestArtifact, validatePreparedRelease, validatePomPromotion, snapshotUpdate, releaseBody, publishRelease};
