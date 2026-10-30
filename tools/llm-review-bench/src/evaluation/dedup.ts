import { ValidationError } from '../util/errors.js';
import { jsonArray, jsonObject, jsonString, parseStrictJson } from '../util/json.js';
import { assertSafeSegment } from '../util/path-safety.js';
import type { DedupCluster, DedupRecord } from '../protocol/types.js';
import { DEDUP_SCHEMA } from '../protocol/types.js';
import type { CompletedReview } from './types.js';

export function collectFindingIds(reviews: CompletedReview[]): Map<string, { agent: string; index: number }> {
  const ids = new Map<string, { agent: string; index: number }>();
  for (const { agent, record } of reviews) {
    for (const [index, finding] of (record.findings ?? []).entries()) {
      const expected = `${agent}:${index}`;
      if (finding.id !== expected) {
        throw new ValidationError(`review ${agent}: finding id ${finding.id} does not match ${expected}`);
      }
      if (ids.has(finding.id)) {
        throw new ValidationError(`duplicate finding id ${finding.id}`);
      }
      ids.set(finding.id, { agent, index });
    }
  }
  return ids;
}

export function identityClusters(reviews: CompletedReview[]): DedupCluster[] {
  const clusters: DedupCluster[] = [];
  let sequence = 0;
  for (const { record } of reviews) {
    for (const finding of record.findings ?? []) {
      sequence += 1;
      clusters.push({ clusterId: `C${sequence.toString().padStart(2, '0')}`, findingIds: [finding.id], note: null });
    }
  }
  return clusters;
}

export function buildIdentityDedup(
  runId: string,
  version: number,
  createdAt: string,
  reviews: CompletedReview[],
): DedupRecord {
  return {
    schemaVersion: DEDUP_SCHEMA,
    runId,
    version,
    createdAt,
    method: 'identity',
    note: 'each finding is its own cluster; semantic deduplication requires explicit clusters input',
    clusters: identityClusters(reviews),
  };
}

export interface ClustersInput {
  method: string;
  note: string;
  clusters: DedupCluster[];
}

export function parseClustersInput(text: string, label: string, knownFindingIds: Set<string>): ClustersInput {
  const root = jsonObject(parseStrictJson(text, label), label);
  const method = root['method'] === undefined ? 'imported' : jsonString(root['method'], `${label}.method`);
  const note = root['note'] === undefined ? '' : jsonString(root['note'], `${label}.note`, { nonEmpty: false });
  const rawClusters = jsonArray(root['clusters'], `${label}.clusters`);
  const clusters: DedupCluster[] = [];
  const clusterIds = new Set<string>();
  const assigned = new Set<string>();
  rawClusters.forEach((value, index) => {
    const item = jsonObject(value, `${label}.clusters[${index}]`);
    const clusterId = item['clusterId'] === undefined
      ? `C${(index + 1).toString().padStart(2, '0')}`
      : assertSafeSegment(jsonString(item['clusterId'], `${label}.clusters[${index}].clusterId`), 'cluster id');
    if (clusterIds.has(clusterId)) {
      throw new ValidationError(`${label}.clusters[${index}].clusterId: duplicate ${clusterId}`);
    }
    clusterIds.add(clusterId);
    const findingIds = jsonArray(item['findingIds'], `${label}.clusters[${index}].findingIds`).map((findingId, findingIndex) =>
      jsonString(findingId, `${label}.clusters[${index}].findingIds[${findingIndex}]`));
    if (findingIds.length === 0) {
      throw new ValidationError(`${label}.clusters[${index}].findingIds: a cluster must contain at least one finding`);
    }
    for (const findingId of findingIds) {
      if (!knownFindingIds.has(findingId)) {
        throw new ValidationError(`${label}.clusters[${index}].findingIds: unknown finding id ${findingId}`);
      }
      if (assigned.has(findingId)) {
        throw new ValidationError(`${label}.clusters[${index}].findingIds: finding ${findingId} appears in more than one cluster`);
      }
      assigned.add(findingId);
    }
    clusters.push({
      clusterId,
      findingIds,
      note: item['note'] === undefined ? null : jsonString(item['note'], `${label}.clusters[${index}].note`, { nonEmpty: false }),
    });
  });
  for (const findingId of knownFindingIds) {
    if (!assigned.has(findingId)) {
      throw new ValidationError(`${label}.clusters: finding ${findingId} is not assigned to any cluster`);
    }
  }
  return { method, note, clusters };
}

export function buildImportedDedup(
  runId: string,
  version: number,
  createdAt: string,
  parsed: ClustersInput,
): DedupRecord {
  return {
    schemaVersion: DEDUP_SCHEMA,
    runId,
    version,
    createdAt,
    method: 'imported',
    note: parsed.note.length > 0 ? `${parsed.method}: ${parsed.note}` : parsed.method,
    clusters: parsed.clusters,
  };
}

export function findingReviewer(findingId: string): string {
  const separator = findingId.indexOf(':');
  if (separator <= 0) {
    throw new ValidationError(`malformed finding id ${findingId}`);
  }
  return findingId.slice(0, separator);
}
