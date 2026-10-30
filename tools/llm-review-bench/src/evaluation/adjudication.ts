import { ValidationError } from '../util/errors.js';
import { jsonArray, jsonEnum, jsonNumber, jsonObject, jsonString, parseStrictJson } from '../util/json.js';
import { assertSafeSegment } from '../util/path-safety.js';
import type { AdjudicationRecord, AdjudicationVerdict, JudgeIdentity, Verdict } from '../protocol/types.js';
import { ADJUDICATION_SCHEMA, VERDICTS } from '../protocol/types.js';

export interface VerdictsInput {
  judge: JudgeIdentity;
  verdicts: AdjudicationVerdict[];
}

function parseJudge(value: unknown, label: string, fallback: JudgeIdentity): JudgeIdentity {
  if (value === undefined || value === null) {
    return fallback;
  }
  const object = jsonObject(value, label);
  return {
    kind: jsonEnum(object['kind'], `${label}.kind`, ['human', 'agent'] as const),
    name: jsonString(object['name'], `${label}.name`),
    model: object['model'] === undefined || object['model'] === null
      ? null
      : jsonString(object['model'], `${label}.model`, { nonEmpty: false }),
    note: object['note'] === undefined || object['note'] === null
      ? null
      : jsonString(object['note'], `${label}.note`, { nonEmpty: false }),
  };
}

export function parseVerdictsInput(
  text: string,
  label: string,
  options: { clusterIds: Set<string>; goldIds: Set<string> | null; fallbackJudge: JudgeIdentity },
): VerdictsInput {
  const root = jsonObject(parseStrictJson(text, label), label);
  const judge = parseJudge(root['judge'], `${label}.judge`, options.fallbackJudge);
  const rawVerdicts = jsonArray(root['verdicts'], `${label}.verdicts`);
  const seen = new Set<string>();
  const verdicts: AdjudicationVerdict[] = rawVerdicts.map((value, index) => {
    const item = jsonObject(value, `${label}.verdicts[${index}]`);
    const clusterId = assertSafeSegment(
      jsonString(item['clusterId'], `${label}.verdicts[${index}].clusterId`),
      'cluster id',
    );
    if (!options.clusterIds.has(clusterId)) {
      throw new ValidationError(`${label}.verdicts[${index}].clusterId: unknown cluster ${clusterId}`);
    }
    if (seen.has(clusterId)) {
      throw new ValidationError(`${label}.verdicts[${index}].clusterId: duplicate verdict for ${clusterId}`);
    }
    seen.add(clusterId);
    const goldLabelIds = item['goldLabelIds'] === undefined
      ? []
      : jsonArray(item['goldLabelIds'], `${label}.verdicts[${index}].goldLabelIds`).map((goldId, goldIndex) => {
          const parsed = assertSafeSegment(
            jsonString(goldId, `${label}.verdicts[${index}].goldLabelIds[${goldIndex}]`),
            'gold id',
          );
          if (options.goldIds !== null && !options.goldIds.has(parsed)) {
            throw new ValidationError(`${label}.verdicts[${index}].goldLabelIds: unknown gold label ${parsed}`);
          }
          return parsed;
        });
    if (new Set(goldLabelIds).size !== goldLabelIds.length) {
      throw new ValidationError(`${label}.verdicts[${index}].goldLabelIds: duplicate gold label id`);
    }
    const verdict = jsonEnum(item['verdict'], `${label}.verdicts[${index}].verdict`, VERDICTS) as Verdict;
    const rationale = item['rationale'] === undefined || item['rationale'] === null
      ? null
      : jsonString(item['rationale'], `${label}.verdicts[${index}].rationale`, { nonEmpty: false });
    const confidence = item['confidence'] === undefined || item['confidence'] === null
      ? null
      : jsonNumber(item['confidence'], `${label}.verdicts[${index}].confidence`, 0, 1);
    if (verdict === 'confirmed' && goldLabelIds.length > 0 && confidence !== null && confidence <= 0) {
      throw new ValidationError(`${label}.verdicts[${index}]: a confirmed match requires positive confidence`);
    }
    return { clusterId, verdict, goldLabelIds, rationale, confidence };
  });
  return { judge, verdicts };
}

export function buildAdjudication(
  runId: string,
  version: number,
  dedupVersion: number,
  createdAt: string,
  input: VerdictsInput,
): AdjudicationRecord {
  return {
    schemaVersion: ADJUDICATION_SCHEMA,
    runId,
    version,
    dedupVersion,
    createdAt,
    judge: input.judge,
    verdicts: input.verdicts,
  };
}
