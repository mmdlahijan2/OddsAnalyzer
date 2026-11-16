# OddsAnalyzer AI market normalizer (Cloudflare Worker)

This worker keeps the OpenAI API key off the Android device. The Android app sends extracted market text and ordered odds to this endpoint; the worker returns schema-constrained canonical labels. Arbitrage arithmetic remains in the Android app.

## Deploy
1. Install Node.js, then open a terminal in `ai-worker/`.
2. Run `npx wrangler login` to connect your Cloudflare account.
3. Create the encrypted API-key secret: `npx wrangler secret put OPENAI_API_KEY` and paste your OpenAI API key only into the terminal prompt. Never send it in chat or add it to GitHub.
4. Deploy with `npx wrangler deploy`.
5. Copy the deployed Worker URL and append `/match`, then paste that HTTPS address into the app's AI endpoint field.
6. In Cloudflare, configure a rate-limiting rule for this Worker before regular/public use. The API key is stored only as a Worker secret; it is not embedded in the APK.

Do not put the OpenAI key in the APK, GitHub source, or the endpoint field. Add rate limits / authentication before public distribution; CORS is not authentication. The worker validates request size, site count, group indexes and odds/outcome array lengths. The app must still reject low-confidence or ambiguous matches.

The model normalizes labels only. It does not verify that two URLs represent the same real-world fixture, verify bookmaker settlement rules, or guarantee arbitrage. Always manually confirm event, period, line, participant, selection order, and current prices.
