import { ValidationError } from '../util/errors.js';
import type { SystemConfig } from '../protocol/types.js';
import { REAL_AGENTS } from '../protocol/config.js';
import type { ReviewAdapter } from './types.js';
import { CodexAdapter } from './codex.js';
import { ClaudeAdapter } from './claude.js';
import { KimiAdapter } from './kimi.js';
import { OcrAdapter } from './ocr.js';
import { KiroAdapter } from './kiro.js';
import { FixtureAdapter } from './fixture.js';

export function createAdapter(system: SystemConfig): ReviewAdapter {
  switch (system.agent) {
    case 'codex':
      return new CodexAdapter();
    case 'claude':
      return new ClaudeAdapter();
    case 'kimi':
      return new KimiAdapter();
    case 'ocr':
      return new OcrAdapter();
    case 'kiro':
      return new KiroAdapter();
    case 'fixture':
      return new FixtureAdapter();
    default:
      throw new ValidationError(
        `unknown agent ${JSON.stringify(system.agent)}; supported agents are ${[...REAL_AGENTS, 'fixture'].join(', ')}`,
      );
  }
}
