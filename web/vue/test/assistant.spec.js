import { test, expect } from '@playwright/test';
test('real browser WASM, Vue modes, streaming, tools and restart', async ({ page }) => {
 const errors=[];page.on('pageerror',e=>errors.push(e.message));await page.goto('/examples/web/');
 const input=page.getByRole('textbox',{name:'Message'});await expect(input).toBeEnabled();
 await input.fill('Use a tool');await page.getByRole('button',{name:'Send message'}).click();
 await expect(page.getByText('Hello from the shared Rust engine.',{exact:true})).toBeVisible();
 await page.getByLabel('Mode',{exact:true}).selectOption('focused');
 await page.getByRole('button',{name:'Restart from: Use a tool'}).click();
 await expect(page.getByLabel('Mode',{exact:true})).toHaveValue('general');
 await expect(page.getByText('Hello from the shared Rust engine.',{exact:true})).toBeVisible();
 expect(await page.evaluate(()=>window.assistantExample.state.messages.filter(m=>m.role==='user').length)).toBe(1);
 expect(errors).toEqual([]);
});
test('clear during a response leaves an empty transcript',async({page})=>{
 await page.goto('/examples/web/');await expect(page.getByRole('textbox',{name:'Message'})).toBeEnabled();
 await page.evaluate(async()=>{window.assistantExample.send('hello');await window.assistantExample.clear();});
 await expect.poll(()=>page.evaluate(()=>window.assistantExample.state.messages.length)).toBe(0);
 await expect(page.getByRole('textbox',{name:'Message'})).toBeEnabled();
});

test('changed mode requires confirmation before truncating history', async ({page}) => {
 await page.goto('/examples/web/');
 const input=page.getByRole('textbox',{name:'Message'});await expect(input).toBeEnabled();
 await input.fill('old question');await page.getByRole('button',{name:'Send message'}).click();
 await expect(page.getByText('Hello from the shared Rust engine.',{exact:true})).toBeVisible();
 await page.evaluate(()=>window.updateExampleMode());
 await page.getByRole('button',{name:'Restart from: old question'}).click();
 const dialog=page.getByRole('dialog',{name:'Choose a restart mode'});await expect(dialog).toBeVisible();
 expect(await page.evaluate(()=>window.assistantExample.state.messages.length)).toBe(2);
 await dialog.getByRole('button',{name:'General / Focused',exact:true}).click();
 await expect(dialog).not.toBeVisible();await expect(page.getByLabel('Mode',{exact:true})).toHaveValue('focused');
 await expect(page.getByText('Hello from the shared Rust engine.',{exact:true})).toBeVisible();
 await page.screenshot({path:'target/assistant-web.png',fullPage:true});
});
