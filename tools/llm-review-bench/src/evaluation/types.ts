import type { ReviewRecord } from '../protocol/types.js';

export interface CompletedReview {
  agent: string;
  attempt: number;
  record: ReviewRecord;
  sha256: string;
}
