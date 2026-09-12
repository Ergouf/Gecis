/**
 * True end-to-end: two sequential messages must both land in assistant bubbles.
 */
describe('Gecis desktop chat', () => {
  it('replies to two consecutive messages', async () => {
    const input = await $('#input');
    await input.waitForDisplayed({ timeout: 60000 });
    await input.waitForEnabled({ timeout: 30000 });

    // Turn 1
    await input.setValue('只回复两个字：收到');
    await browser.keys('Enter');
    await browser.waitUntil(
      async () => {
        const assistants = await $$('.message.assistant .bubble');
        for (const el of assistants) {
          const t = (await el.getText()).trim();
          if (t.includes('收到')) return true;
        }
        return false;
      },
      { timeout: 120000, timeoutMsg: 'first reply missing' },
    );

    // Composer must be usable again for turn 2.
    await browser.waitUntil(
      async () => {
        const enabled = await input.isEnabled();
        const sendBtn = await $('#send');
        const sendEnabled = await sendBtn.isEnabled();
        return enabled && sendEnabled;
      },
      { timeout: 20000, timeoutMsg: 'composer stayed locked after first reply' },
    );

    // Turn 2
    await input.click();
    await input.setValue('再回复两个字：好的');
    await browser.keys('Enter');

    await browser.waitUntil(
      async () => {
        const assistants = await $$('.message.assistant .bubble');
        for (const el of assistants) {
          const t = (await el.getText()).trim();
          if (t.includes('好的')) return true;
        }
        return false;
      },
      { timeout: 120000, timeoutMsg: 'second reply missing — cannot send follow-up' },
    );

    const assistants = await $$('.message.assistant .bubble');
    let all = '';
    for (const el of assistants) all += (await el.getText()) + '\n';
    console.log('E2E replies:', all.trim());
    expect(all).toContain('收到');
    expect(all).toContain('好的');
  });
});
