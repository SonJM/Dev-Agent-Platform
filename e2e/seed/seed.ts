// Synthetic seed data generator for Dev Agent (skills and routines). Deterministic:
// same SEED -> same data.
//   node --experimental-strip-types seed/seed.ts [--dry-run]
// Scale: 07 section 3 assumes "tens" of skills and routines; seed = 2x of 30 skills = 60 skills.
// Routines are capped at 50 by the app (RoutineService.MAX_ROUTINES), so 30 are seeded and
// 20 slots stay free for the tests. Seeded routines are disabled: they never trigger a run.
// Injection path: the app's own HTTP API (/api/skills, /api/routines).
import { mulberry32, pick, intBetween } from './rng.ts';
import { boundaryString, isBoundaryRow } from './boundary.ts';
import { baseUrl } from '../support/auth.ts';

const SEED = 20261124;
const SKILL_COUNT = 60;
const ROUTINE_COUNT = 30;
const SKILL_NAME_MAX = 47;
const SKILL_DESC_MAX = 300;
const SKILL_BODY_MAX = 40_000;
const ROUTINE_NAME_MAX = 80;
const ROUTINE_QUESTION_MAX = 4_000;
// Must equal GITLAB_PROJECTS in scripts/start-app.sh (the routine API does not check it).
const SEED_PROJECT = 'qa-group/qa-sample';
const WORDS = ['배포', '점검', '검토', '정리', '요청', '확인', '보고', '일정'] as const;
const DAY_SETS = ['', 'MON,TUE,WED,THU,FRI', 'SAT,SUN', 'MON,WED,FRI'] as const;

type Skill = { name: string; description: string; body: string; boundary: boolean };
type Routine = { name: string; question: string; runAt: string; days: string; boundary: boolean };

function skillName(index: number, rng: () => number, boundary: boolean): string {
  if (!boundary) return `qa-seed-skill-${String(index + 1).padStart(3, '0')}`;
  // Name rule: [a-z0-9][a-z0-9-]{1,46}. Edge: shortest (2) and longest (47) names.
  return pick(rng, [`s${index % 10}`, 'a'.repeat(SKILL_NAME_MAX)]);
}

export function generateSkills(seed: number = SEED): Skill[] {
  const rng = mulberry32(seed);
  const used = new Set<string>();
  return Array.from({ length: SKILL_COUNT }, (_, index) => {
    const boundary = isBoundaryRow(rng);
    let name = skillName(index, rng, boundary);
    if (used.has(name)) name = `qa-seed-dup-${index}`;
    used.add(name);
    const words = Array.from({ length: intBetween(rng, 1, 3) }, () => pick(rng, WORDS)).join(' ');
    return {
      name,
      description: boundary ? boundaryString(rng, SKILL_DESC_MAX) : `${words} 지침 ${index + 1}`,
      body: boundary ? boundaryString(rng, intBetween(rng, 0, 1) ? SKILL_BODY_MAX : 1_501) : `- ${words} 규칙 ${index + 1}`,
      boundary,
    };
  });
}

export function generateRoutines(seed: number = SEED + 1): Routine[] {
  const rng = mulberry32(seed);
  return Array.from({ length: ROUTINE_COUNT }, (_, index) => {
    const boundary = isBoundaryRow(rng);
    const hour = String(intBetween(rng, 0, 23)).padStart(2, '0');
    const minute = String(pick(rng, [0, 30, 59])).padStart(2, '0');
    const label = pick(rng, WORDS);
    return {
      name: boundary ? boundaryString(rng, ROUTINE_NAME_MAX).trim() || 'a' : `qa-seed-routine-${index + 1} ${label}`,
      question: boundary ? boundaryString(rng, ROUTINE_QUESTION_MAX) : `${label} 현황을 정리해 줘 ${index + 1}`,
      runAt: boundary ? pick(rng, ['00:00', '23:59']) : `${hour}:${minute}`,
      days: pick(rng, DAY_SETS),
      boundary,
    };
  });
}

async function post(base: string, path: string, form: Record<string, string>): Promise<{ status: number; body: string }> {
  const res = await fetch(`${base}${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(form),
  });
  return { status: res.status, body: await res.text() };
}

function assertSaved(label: string, res: { status: number; body: string }): void {
  if (res.status !== 200) throw new Error(`seed ${label} failed: HTTP ${res.status}`);
  if (!res.body.includes('"ok":true')) throw new Error(`seed ${label} rejected: ${res.body.slice(0, 120)}`);
}

async function inject(skills: readonly Skill[], routines: readonly Routine[]): Promise<void> {
  const base = baseUrl();
  for (const skill of skills) {
    const res = await post(base, '/api/skills', { name: skill.name, description: skill.description, body: skill.body, stages: '', enabled: 'true' });
    assertSaved(`skill ${skill.name}`, res);
  }
  for (const routine of routines) {
    const res = await post(base, '/api/routines', {
      name: routine.name, question: routine.question, projects: SEED_PROJECT, runAt: routine.runAt,
      days: routine.days, verify: 'false', notify: 'false', enabled: 'false',
    });
    assertSaved(`routine ${routine.name.slice(0, 20)}`, res);
  }
}

async function main(): Promise<void> {
  const dryRun = process.argv.slice(2).includes('--dry-run');
  const skills = generateSkills();
  const routines = generateRoutines();
  if (dryRun) console.log(JSON.stringify({ skills, routines }));
  else await inject(skills, routines);
  const boundary = [...skills, ...routines].filter((record) => record.boundary).length;
  console.log(`seed: ${skills.length + routines.length} rows, boundary ${boundary}`);
}

main().catch((error: Error) => {
  console.error(error.message);
  process.exit(1);
});
