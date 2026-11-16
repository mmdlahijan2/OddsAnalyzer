export default {
  async fetch(request, env) {
    const cors = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type",
      "Content-Type": "application/json; charset=utf-8"
    };
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
    const url = new URL(request.url);
    if (url.pathname !== "/match" || request.method !== "POST")
      return json({ error: "Not found" }, 404, cors);
    if (!env.OPENAI_API_KEY) return json({ error: "Server is missing OPENAI_API_KEY" }, 503, cors);
    try {
      const raw = await request.text();
      if (raw.length > 900000) return json({ error: "Payload too large" }, 413, cors);
      const body = JSON.parse(raw);
      if (!Array.isArray(body.sites) || body.sites.length < 2 || body.sites.length > 4)
        return json({ error: "Expected 2 to 4 sites" }, 400, cors);
      const sites = body.sites.map((s, si) => ({
        site_index: si,
        host: String(s.host || "").slice(0, 120),
        page_title: String(s.title || "").slice(0, 240),
        groups: (Array.isArray(s.groups) ? s.groups : []).slice(0, 100).map((g, gi) => ({
          group_index: gi,
          text: String(g.text || "").slice(0, 700),
          odds: (Array.isArray(g.odds) ? g.odds : []).slice(0, 3).map(x => String(x).slice(0, 24))
        }))
      }));
      const schema = {
        type: "object",
        additionalProperties: false,
        properties: {
          markets: {
            type: "array",
            items: {
              type: "object",
              additionalProperties: false,
              properties: {
                site_index: { type: "integer" },
                group_index: { type: "integer" },
                market_type: { type: "string", enum: ["TOTAL_GOALS", "BTTS", "MATCH_RESULT", "DOUBLE_CHANCE", "TEAM_TOTAL", "ASIAN_HANDICAP", "UNKNOWN"] },
                period: { type: "string", enum: ["FT", "1H", "2H", "ET", "UNKNOWN"] },
                line: { type: "string" },
                scope: { type: "string" },
                participant: { type: "string" },
                outcomes: { type: "array", items: { type: "string" } },
                confidence: { type: "number" },
                reason: { type: "string" }
              },
              required: ["site_index", "group_index", "market_type", "period", "line", "scope", "participant", "outcomes", "confidence", "reason"]
            }
          }
        },
        required: ["markets"]
      };
      const instructions = `You are a strict sports betting market data normalizer. Do NOT calculate arbitrage, predict results, or invent missing data. Each input group has ordered decimal odds; preserve that exact order when returning outcomes. Analyze each group independently.
Canonical market types:
TOTAL_GOALS: match total goals; outcomes exactly OVER, UNDER; line is the exact goal line.
BTTS: both teams to score; outcomes YES, NO; line "NONE".
MATCH_RESULT: 1X2; outcomes HOME, DRAW, AWAY in the exact same order as the source group.
DOUBLE_CHANCE: outcomes chosen from HOME_DRAW, HOME_AWAY, DRAW_AWAY in source order.
TEAM_TOTAL: one named team's total; outcomes OVER, UNDER; participant must identify the team and line must be exact.
ASIAN_HANDICAP: handicap market; return outcomes HOME, AWAY in source order and line must preserve the handicap (including sign and team perspective) in a normalized string.
period must be FT, 1H, 2H, or ET only when clearly stated. If unclear, use UNKNOWN.
scope should be MATCH except team-specific markets, where use TEAM.
participant is the normalized team name for TEAM_TOTAL; otherwise "NONE".
Use UNKNOWN market type if uncertain. Never infer a missing line, period, team, or selection. Return outcomes array with one entry per supplied odds value; if labels cannot be safely mapped, return "UNKNOWN" for those positions. Do not reorder outcomes. Confidence 0..1 measures confidence in this group's interpretation, not betting value. Ignore promotional numbers, limits, scores, timers, and unrelated numeric text. A group with malformed odds or unclear market must be marked UNKNOWN or confidence below 0.85.`;
      const aiResponse = await fetch("https://api.openai.com/v1/responses", {
        method: "POST",
        headers: {
          "Authorization": "Bearer " + env.OPENAI_API_KEY,
          "Content-Type": "application/json"
        },
        body: JSON.stringify({
          model: env.OPENAI_MODEL || "gpt-6-astra",
          instructions,
          input: JSON.stringify(sites),
          text: {
            format: {
              type: "json_schema",
              name: "odds_market_normalization",
              strict: true,
              schema
            }
          },
          max_output_tokens: 12000
        })
      });
      const apiText = await aiResponse.text();
      if (!aiResponse.ok) return json({ error: "Model API error", status: aiResponse.status, detail: apiText.slice(0, 1200) }, 502, cors);
      const response = JSON.parse(apiText);
      let outputText = "";
      for (const item of (response.output || [])) {
        if (item.type === "message") {
          for (const c of (item.content || [])) if (c.type === "output_text") outputText += c.text || "";
        }
      }
      if (!outputText) return json({ error: "Model returned no structured output" }, 502, cors);
      const normalized = JSON.parse(outputText);
      const safe = (normalized.markets || []).filter(m =>
        Number.isInteger(m.site_index) && m.site_index >= 0 && m.site_index < sites.length &&
        Number.isInteger(m.group_index) && m.group_index >= 0 &&
        m.group_index < sites[m.site_index].groups.length &&
        Array.isArray(m.outcomes) &&
        m.outcomes.length === sites[m.site_index].groups[m.group_index].odds.length &&
        Number.isFinite(m.confidence) && m.confidence >= 0 && m.confidence <= 1
      );
      return json({ markets: safe, model: env.OPENAI_MODEL || "gpt-6-astra" }, 200, cors);
    } catch (e) {
      return json({ error: "Request failed", detail: String(e).slice(0, 500) }, 500, cors);
    }
  }
};
function json(data, status, headers) {
  return new Response(JSON.stringify(data), { status, headers });
}
