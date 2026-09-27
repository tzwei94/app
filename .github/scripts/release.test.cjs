'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const policy = require('./release.cjs');

const sha = 'a'.repeat(40);
const repository = 'example/banking-api';
const imageRepository = 'registry.example/docker/banking-api';
const pom = version => `<?xml version="1.0"?>\n<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><parent><version>4.1.1</version></parent><!-- <version>9.9.9</version> --><artifactId>banking-api</artifactId><version>${version}</version><dependencies><dependency><version>5.0.0</version></dependency></dependencies></project>\n`;
const run = () => ({id: 42, run_attempt: 2, event: 'push', head_branch: 'main', head_sha: sha,
  status: 'completed', conclusion: 'success', path: '.github/workflows/app-ci.yml',
  repository: {full_name: repository}, head_repository: {full_name: repository}});
const manifest = () => ({schema_version: 1, image: `${imageRepository}@sha256:${'b'.repeat(64)}`,
  source_sha: sha, version: '0.1.0', repository, run_id: 42, run_attempt: 2, workflow: 'app-ci.yml'});
const preparedPR = () => ({number: 7, merged_at: '2026-09-25T12:00:00Z', merge_commit_sha: sha,
  user: {login: 'release-bot[bot]'}, base: {ref: 'main', repo: {full_name: repository}},
  head: {ref: 'release/prepare-v0.1.0', repo: {full_name: repository}},
  body: '<!-- release-preparation:v0.1.0 -->'});

test('reads and replaces only the direct project version, preserving all other XML', () => {
  assert.equal(typeof policy.readPomVersion, 'function', 'release policy must read the committed project version');
  assert.equal(policy.readPomVersion(pom('0.1.0-SNAPSHOT')), '0.1.0-SNAPSHOT');
  assert.equal(policy.replacePomVersion(pom('0.1.0-SNAPSHOT'), '0.1.0-SNAPSHOT', '0.1.0'), pom('0.1.0'));
});

test('refuses ambiguous, inherited, interpolated, or stale project versions', () => {
  for (const xml of ['<project><parent><version>1.0.0</version></parent></project>',
    '<project><version>1.0.0</version><version>2.0.0</version></project>',
    '<project><version>${revision}</version></project>', '<project><version>1.0.0</project>']) {
    assert.throws(() => policy.readPomVersion(xml));
  }
  assert.throws(() => policy.replacePomVersion(pom('0.2.0-SNAPSHOT'), '0.1.0-SNAPSHOT', '0.1.0'));
});

test('initial patch finalizes the existing snapshot; subsequent versions use the last release', () => {
  const cases = [
    ['0.1.0-SNAPSHOT', null, 'patch', '0.1.0'],
    ['0.1.0-SNAPSHOT', null, 'minor', '0.2.0'],
    ['0.1.0-SNAPSHOT', null, 'major', '1.0.0'],
    ['0.1.1-SNAPSHOT', '0.1.0', 'patch', '0.1.1'],
    ['0.1.1-SNAPSHOT', '0.1.0', 'minor', '0.2.0'],
    ['0.1.1-SNAPSHOT', '0.1.0', 'major', '1.0.0'],
  ];
  for (const [current, latest, bump, expected] of cases) {
    assert.equal(policy.nextReleaseVersion(current, latest, bump), expected);
  }
  assert.equal(policy.nextSnapshotVersion('2.3.9'), '2.3.10-SNAPSHOT');
  for (const args of [['0.1.0', null, 'patch'], ['0.2.0-SNAPSHOT', '0.1.0', 'patch'],
    ['0.1.0-SNAPSHOT', null, 'other']]) assert.throws(() => policy.nextReleaseVersion(...args));
  for (const value of ['01.1.0', '1.0', 'v1.0.0', '1.0.0-rc.1', '9007199254740992.0.0']) {
    assert.throws(() => policy.parseVersion(value));
  }
});

test('preparation rejects overlapping PRs and already reserved release versions', () => {
  const releases = [{tag_name: 'v0.1.0', draft: false, prerelease: false},
    {tag_name: 'v8.0.0', draft: true, prerelease: false}];
  assert.equal(policy.preparationVersion('0.1.1-SNAPSHOT', releases, [], 'patch'), '0.1.1');
  assert.throws(() => policy.preparationVersion('0.1.1-SNAPSHOT', releases,
    [{head: {ref: 'release/prepare-v0.2.0'}}], 'patch'));
  assert.throws(() => policy.preparationVersion('0.1.1-SNAPSHOT',
    [...releases, {tag_name: 'v0.1.1', draft: true}], [], 'patch'));
});

test('accepts only successful main push runs from the application CI in this repository', () => {
  assert.equal(policy.validateRun(run(), repository, '42'), undefined);
  for (const change of [{id: 43}, {run_attempt: 0}, {event: 'pull_request'}, {head_branch: 'other'},
    {conclusion: 'failure'}, {status: 'in_progress'}, {path: '.github/workflows/release.yml'},
    {repository: {full_name: 'other/repo'}}, {head_repository: {full_name: 'fork/repo'}}, {head_sha: 'short'}]) {
    assert.throws(() => policy.validateRun({...run(), ...change}, repository, '42'));
  }
  assert.throws(() => policy.validateRun(run(), repository, '42garbage'));
});

test('manifest binds the exact run attempt, source, project version, and registry digest', () => {
  assert.equal(policy.validateManifest(manifest(), run(), '0.1.0', imageRepository), undefined);
  for (const change of [{schema_version: 2}, {run_id: 43}, {run_attempt: 1}, {source_sha: 'c'.repeat(40)},
    {version: '0.1.0-SNAPSHOT'}, {version: '0.2.0'}, {repository: 'other/repo'}, {workflow: 'release.yml'},
    {image: `${imageRepository}:latest`}, {image: `evil.example/image@sha256:${'b'.repeat(64)}`}]) {
    assert.throws(() => policy.validateManifest({...manifest(), ...change}, run(), '0.1.0', imageRepository));
  }
});

test('artifact selection refuses missing, expired, duplicate, and unrelated artifacts', () => {
  const artifact = {id: 123, name: 'image-manifest', expired: false,
    workflow_run: {id: 42, head_sha: sha, head_branch: 'main'}};
  assert.equal(policy.selectManifestArtifact([artifact], run()), 123);
  for (const artifacts of [[], [artifact, {...artifact, id: 124}], [{...artifact, expired: true}],
    [{...artifact, workflow_run: {...artifact.workflow_run, head_sha: 'c'.repeat(40)}}]]) {
    assert.throws(() => policy.selectManifestArtifact(artifacts, run()));
  }
});

test('release requires the App preparation PR merged at this exact CI commit', () => {
  assert.equal(policy.validatePreparedRelease([preparedPR()], sha, '0.1.0', repository, 'release-bot[bot]').number, 7);
  for (const change of [{merged_at: null}, {merge_commit_sha: 'c'.repeat(40)}, {body: ''},
    {user: {login: 'someone'}}, {head: {ref: 'ordinary-branch', repo: {full_name: repository}}}]) {
    assert.throws(() => policy.validatePreparedRelease([{...preparedPR(), ...change}], sha, '0.1.0', repository, 'release-bot[bot]'));
  }
  assert.equal(policy.validatePomPromotion(pom('0.1.0-SNAPSHOT'), pom('0.1.0'), '0.1.0'), undefined);
  assert.throws(() => policy.validatePomPromotion(pom('0.1.0-SNAPSHOT'), pom('0.1.0').replace('4.1.1', '4.2.0'), '0.1.0'));
});

test('next-development update preserves a newer or already updated main version', () => {
  assert.equal(policy.snapshotUpdate('0.1.0', '0.1.0'), '0.1.1-SNAPSHOT');
  assert.equal(policy.snapshotUpdate('0.1.0', '0.1.1-SNAPSHOT'), null);
  assert.equal(policy.snapshotUpdate('0.1.0', '0.2.0'), null);
});

function releaseAPI({tagSha = null, release = null, assetManifest = null, parsedAsset = false} = {}) {
  const calls = [];
  const absent = () => { throw Object.assign(new Error('Not found'), {status: 404}); };
  const github = {paginate: async endpoint => endpoint(), rest: {
    git: {getRef: async () => tagSha ? {data: {object: {type: 'commit', sha: tagSha}}} : absent(),
      createRef: async args => { calls.push(['tag', args]); return {data: {}}; }},
    repos: {listReleases: async () => release ? [{...release, tag_name: 'v0.1.0'}] : [],
      getReleaseByTag: async () => release && !release.draft ? {data: release} : absent(),
      createRelease: async args => { calls.push(['draft', args]); return {data: {id: 9, draft: args.draft, body: args.body, upload_url: 'https://uploads.example', html_url: 'https://github.example/releases/v0.1.0'}}; },
      listReleaseAssets: async () => ({data: assetManifest ? [{id: 10, name: 'image-manifest.json'}] : []}),
      getReleaseAsset: async () => ({data: parsedAsset ? assetManifest : Buffer.from(JSON.stringify(assetManifest))}),
      uploadReleaseAsset: async args => { calls.push(['asset', args]); return {data: {}}; },
      updateRelease: async args => { calls.push(['publish', args]); return {data: {html_url: 'https://github.example/releases/v0.1.0'}}; }},
  }};
  return {github, calls};
}

test('publishes the exact manifest before making the immutable release public', async () => {
  const {github, calls} = releaseAPI();
  await policy.publishRelease(github, {owner: 'example', repo: 'banking-api'}, manifest());
  assert.deepEqual(calls.map(([event]) => event), ['tag', 'draft', 'asset', 'publish']);
  assert.equal(calls[0][1].sha, sha);
  assert.equal(calls[0][1].ref, 'refs/tags/v0.1.0');
  assert.equal(calls[1][1].draft, true);
  assert.deepEqual(JSON.parse(calls[2][1].data.toString()), manifest());
  assert.equal(calls[3][1].draft, false);
});

test('conflicting tags or release mappings never get overwritten', async () => {
  const conflict = releaseAPI({tagSha: 'c'.repeat(40)});
  await assert.rejects(policy.publishRelease(conflict.github, {owner: 'example', repo: 'banking-api'}, manifest()));
  assert.deepEqual(conflict.calls, []);
  const changed = releaseAPI({tagSha: sha, release: {id: 9, draft: false, body: policy.releaseBody(manifest())},
    assetManifest: {...manifest(), image: `${imageRepository}@sha256:${'d'.repeat(64)}`}});
  await assert.rejects(policy.publishRelease(changed.github, {owner: 'example', repo: 'banking-api'}, manifest()));
  assert.deepEqual(changed.calls, []);
});

test('identical release retries are read-only and a matching partial draft can recover', async () => {
  const existing = {id: 9, draft: false, body: policy.releaseBody(manifest()), html_url: 'release-url'};
  const retry = releaseAPI({tagSha: sha, release: existing, assetManifest: manifest()});
  assert.equal(await policy.publishRelease(retry.github, {owner: 'example', repo: 'banking-api'}, manifest()), 'release-url');
  assert.deepEqual(retry.calls, []);
  const partial = releaseAPI({tagSha: sha, release: {...existing, draft: true}});
  await policy.publishRelease(partial.github, {owner: 'example', repo: 'banking-api'}, manifest());
  assert.deepEqual(partial.calls.map(([event]) => event), ['asset', 'publish']);
});

test('release retries accept a JSON-decoded asset response but still reject a changed manifest', async () => {
  const existing = {id: 9, draft: false, body: policy.releaseBody(manifest()), html_url: 'release-url'};
  const retry = releaseAPI({tagSha: sha, release: existing, assetManifest: manifest(), parsedAsset: true});
  assert.equal(await policy.publishRelease(retry.github, {owner: 'example', repo: 'banking-api'}, manifest()), 'release-url');
  assert.deepEqual(retry.calls, []);
  const changed = releaseAPI({tagSha: sha, release: existing,
    assetManifest: {...manifest(), source_sha: 'd'.repeat(40)}, parsedAsset: true});
  await assert.rejects(policy.publishRelease(changed.github, {owner: 'example', repo: 'banking-api'}, manifest()), /conflicts/);
  assert.deepEqual(changed.calls, []);
});
