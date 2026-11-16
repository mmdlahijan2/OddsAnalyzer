# OddsAnalyzer AI market normalizer (Cloudflare Worker)

This worker keeps the OpenAI API key off the Android device. The Android app sends extracted market text and ordered odds to this endpoint; the worker returns schema-constrained canonical labels. Arbitrage arithmetic remains in the Android app.

## Deploy
1. Create a Cloudflare Worker and paste `src/index.js` (or deploy this folder with Wrangler).
2. Set the secret: `npx wrangler secret put OPENAI_API_KEY`.
3. Optional model variable: `OPENAI_MODEL=gpt-6-astra` (change only to a model enabled for your API project).
4. Deploy. The endpoint will be `https://<worker-name>.<account>.workers.dev/match`.
5. Paste that HTTPS endpoint into the app's AI endpoint field.

Do not put the OpenAI key in the APK, GitHub source, or the endpoint field. Add rate limits / authentication before public distribution; CORS is not authentication. The worker validates request size, site count, group indexes and odds/outcome array lengths. The app must still reject low-confidence or ambiguous matches.

The model normalizes labels only. It does not verify that two URLs represent the same real-world fixture, verify bookmaker settlement rules, or guarantee arbitrage. Always manually confirm event, period, line, participant, selection order, and current prices.
