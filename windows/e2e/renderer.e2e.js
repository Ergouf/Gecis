describe('Gecis history renderer', () => {
  it('renders every supported math delimiter after history reload', async () => {
    await $('#input').waitForDisplayed({ timeout: 30_000 });
    await browser.waitUntil(
      async () => browser.execute(() => Boolean(window.GecisChat?.onHistory)),
      { timeout: 30_000, timeoutMsg: 'frontend bridge did not initialize' },
    );
    const snapshot = {
      projects: [{
        id: 1,
        name: '公式测试',
        conversations: [{ id: 9, title: '历史公式', preview: '公式', updatedAt: Date.now() }],
      }],
      currentProjectId: 1,
      currentConversationId: 9,
      messages: [
        { role: 'user', content: '解释这些公式' },
        {
          role: 'assistant',
          content: '行内 $a^2+b^2=c^2$ 与 \\(x+1\\)。\n\n$$\\frac{A}{B}$$\n\n\\[\\sum_{i=1}^n i\\]\n\n代码 `$not_math$`',
        },
      ],
    };
    await browser.execute((value) => window.GecisChat.onHistory(value, true), snapshot);
    await browser.waitUntil(
      async () => (await $$('.message.assistant .katex')).length >= 4,
      { timeout: 15_000, timeoutMsg: 'historical formulas were not rendered' },
    );
    expect(await $$('.message.assistant .katex-display')).toHaveLength(2);
    const code = await $('.message.assistant code');
    expect(await code.getText()).toBe('$not_math$');
  });
});
