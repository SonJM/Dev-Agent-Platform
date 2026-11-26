// admin.js 의 묶음 판별 함수(순수 함수 블록)를 잘라 내 변형 입력으로 실행한다.
// 실행: node docs/tasks/results/T-025/bundle-type-check.mjs
import fs from 'fs';
const src = fs.readFileSync('src/main/resources/static/admin.js', 'utf8');
const a = src.indexOf('const BUNDLE_FORMAT');
const b = src.indexOf('let bundlePending');
const api = new Function(src.slice(a, b) + '\nreturn { bundleIsRemote, bundleStdio, bundleVisible };')();
const ch = n => String.fromCharCode(n);
const cases = [
  ['stdio + U+001F', 'stdio' + ch(0x1f), false],
  ['" STDIO "', ' STDIO ', false],
  ['stdio + U+00A0', 'stdio' + ch(0xa0), false],
  ['누락(undefined)', undefined, false],
  ['null', null, false],
  ['배열', ['http'], false],
  ['"http"', 'http', true],
  ['" HTTP "', ' HTTP ', true],
  ['"sse"', 'sse', true],
  ['http + U+001F (Java strip 이 지움)', 'http' + ch(0x1f), true],
  ['http + U+00A0 (Java strip 이 안 지움)', 'http' + ch(0xa0), false],
];
let fail = 0;
for (const [name, type, remote] of cases) {
  const warn = !api.bundleIsRemote(type);
  const ok = warn === !remote;
  if (!ok) fail++;
  console.log(`${ok ? 'OK  ' : 'FAIL'} ${name.padEnd(36)} -> ${warn ? '경고 대상' : '원격(경고 없음)'}`);
}
const list = api.bundleStdio({ mcpServers: [{ name: 'x', type: 'stdio' + ch(0x1f), command: ' cmd ', args: ' a ' + '\r\n' + '  ' + '\n' + 'b' }] });
const shown = api.bundleVisible([list[0].command, ...list[0].args].join('\n') + ch(0x202e));
console.log('stdio 목록 수:', list.length, '| command:', JSON.stringify(list[0].command), '| args:', JSON.stringify(list[0].args));
console.log('가시 표기:', JSON.stringify(shown));
if (list.length !== 1 || shown.indexOf('202E') < 0) fail++;
console.log(fail ? `실패 ${fail}건` : '전부 통과');
process.exit(fail ? 1 : 0);
