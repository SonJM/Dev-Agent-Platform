// Boundary-value generators. Shapes come from 03 (column types, constraints) and 05 (field rules),
// never from real data.
import { pick, intBetween } from './rng.ts';

export const BOUNDARY_RATIO = 0.05;

type Rng = () => number;

const HANGUL = '가나다라마바사아자차카타파하';
const SPECIAL = `<script>alert(1)</script> ' " \\ % _ ; -- &amp;`;
const EMOJI = '😀🚀👨‍👩‍👧‍👦🇰🇷';

// allowEmpty: only for columns/fields that accept empty or whitespace-only values.
export function boundaryString(rng: Rng, maxLen: number, allowEmpty = false): string {
  const fill = (unit: string): string => Array.from(unit.repeat(Math.ceil(maxLen / unit.length))).slice(0, maxLen).join('');
  const cases: string[] = [
    fill('x'),
    fill(HANGUL),
    Array.from(EMOJI).slice(0, Math.max(1, Math.min(maxLen, 4))).join(''),
    SPECIAL.slice(0, maxLen),
    `  ${fill('y').slice(0, Math.max(1, maxLen - 4))}  `,
    'a',
    ...(allowEmpty ? ['', '   '] : []),
  ];
  return pick(rng, cases);
}

export function boundaryInt(rng: Rng, min: number, max: number): number {
  const cases = [min, max, 0, -1, min + 1, max - 1, min].filter((n) => n >= min && n <= max);
  return cases.length > 0 ? pick(rng, cases) : intBetween(rng, min, max);
}

// ISO dates on calendar edges: month end, leap day, year end/start, midnight.
export function boundaryDate(rng: Rng): string {
  return pick(rng, [
    '2024-02-29T00:00:00.000Z',
    '2025-02-28T23:59:59.999Z',
    '2025-12-31T23:59:59.999Z',
    '2026-01-01T00:00:00.000Z',
    '2026-03-31T15:00:00.000Z',
    '1970-01-01T00:00:00.000Z',
  ]);
}

export function isBoundaryRow(rng: Rng): boolean {
  return rng() < BOUNDARY_RATIO;
}
