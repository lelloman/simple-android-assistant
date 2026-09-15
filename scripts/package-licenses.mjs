import { copyFileSync, mkdirSync } from 'node:fs';
mkdirSync(new URL('../target/packages/', import.meta.url), { recursive: true });
for (const pkg of ['core', 'vue', 'providers']) {
  for (const file of ['LICENSE', 'NOTICE']) copyFileSync(new URL(`../${file}`, import.meta.url), new URL(`../web/${pkg}/${file}`, import.meta.url));
}
