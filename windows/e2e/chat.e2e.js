/**
 * True end-to-end: launch the real desktop binary, drive the chat UI,
 * and assert the assistant bubble contains a model reply.
 */
describe('Gecis desktop chat', () => {
  it('replies in the assistant bubble after sending a message', async () => {
    const input = await $('#input');
    await input.waitForDisplayed({ timeout: 60000 });
    await input.waitForEnabled({ timeout: 30000 });

    await input.setValue('只回复两个字：收到');
    await browser.keys('Enter');

    await browser.waitUntil(
      async () => {
        const users = await $$('.message.user .bubble');
        for (const el of users) {
          const t = await el.getText();
          if (t.includes('收到')) return true;
        }
        return false;
      },
      { timeout: 20000, timeoutMsg: 'user bubble did not appear' },
    );

    await browser.waitUntil(
      async () => {
        const assistants = await $$('.message.assistant .bubble');
        for (const el of assistants) {
          const text = (await el.getText()).trim();
          if (text.length < 2) continue;
          if (text.startsWith('Tauri bridge')) continue;
          if (text.startsWith('发生错误：未找到 Antigravity')) continue;
          return true;
        }
        return false;
      },
      { timeout: 120000, timeoutMsg: 'assistant bubble never received a model reply' },
    );

    const assistants = await $$('.message.assistant .bubble');
    let reply = '';
    for (const el of assistants) {
      const text = (await el.getText()).trim();
      if (text.length > reply.length) reply = text;
    }
    console.log('E2E reply:', reply);
    expect(reply.length).toBeGreaterThan(0);
  });
});
