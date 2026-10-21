import { buildLeaderboard, formatLeaderboard } from '../evaluation/leaderboard.js';
import { printJson, resolveBenchRoot } from './shared.js';

export async function leaderboardCommand(options: {
  output?: string;
  includeFixture?: boolean;
  json?: boolean;
}): Promise<number> {
  const root = resolveBenchRoot(options.output);
  const result = await buildLeaderboard(root, options.includeFixture === true);
  if (options.json === true) {
    printJson(result);
  } else {
    process.stdout.write(formatLeaderboard(result));
  }
  return 0;
}
