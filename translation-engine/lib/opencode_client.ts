const BASE_URL = Deno.env.get("OPENCODE_SERVER_URL") || "http://localhost:4096";
const USERNAME = Deno.env.get("OPENCODE_SERVER_USERNAME") || "georg";
const PASSWORD = Deno.env.get("OPENCODE_SERVER_PASSWORD") || "home5home";

function authHeaders(): Record<string, string> {
  const encoded = btoa(`${USERNAME}:${PASSWORD}`);
  return {
    "Content-Type": "application/json",
    Authorization: `Basic ${encoded}`,
  };
}

export interface ModelRef {
  providerID: string;
  modelID: string;
}

export interface SendOptions {
  model?: ModelRef;
  system?: string;
}

export class OpencodeClient {
  async createSession(directory?: string): Promise<string> {
    const url = directory
      ? `${BASE_URL}/session?directory=${encodeURIComponent(directory)}`
      : `${BASE_URL}/session`;
    const res = await fetch(url, {
      method: "POST",
      headers: authHeaders(),
      body: "{}",
    });
    if (!res.ok) {
      throw new Error(`createSession failed: ${res.status} ${await res.text()}`);
    }
    const data = await res.json();
    const sessionId: string | undefined =
      data.sessionId || data.sessionID || data.id;
    if (!sessionId) {
      throw new Error(
        `createSession: no sessionId in response: ${JSON.stringify(data)}`,
      );
    }
    return sessionId;
  }

  async sendMessage(
    sessionId: string,
    text: string,
    opts?: SendOptions,
    signal?: AbortSignal,
  ): Promise<string> {
    const body: Record<string, unknown> = {
      parts: [{ type: "text", text }],
    };

    if (opts?.system) {
      body.system = opts.system;
    }
    if (opts?.model) {
      body.model = {
        providerID: opts.model.providerID,
        modelID: opts.model.modelID,
      };
    }

    const res = await fetch(`${BASE_URL}/session/${sessionId}/message`, {
      method: "POST",
      headers: authHeaders(),
      body: JSON.stringify(body),
      signal,
    });
    if (!res.ok) {
      throw new Error(
        `sendMessage failed: ${res.status} ${await res.text()}`,
      );
    }
    const data = await res.json();
    const parts = data.parts as Array<{ type: string; text?: string }> | undefined;
    if (parts) {
      const textPart = parts.find((p) => p.type === "text" || p.type === "tool-use");
      if (textPart?.text) return textPart.text;
    }
    return data.message || data.text || JSON.stringify(data);
  }

  async sendMessageAsync(
    sessionId: string,
    text: string,
    opts?: SendOptions,
  ): Promise<void> {
    const body: Record<string, unknown> = {
      parts: [{ type: "text", text }],
    };
    if (opts?.system) body.system = opts.system;
    if (opts?.model) body.model = opts.model;
    const res = await fetch(
      `${BASE_URL}/session/${sessionId}/prompt_async`,
      {
        method: "POST",
        headers: authHeaders(),
        body: JSON.stringify(body),
      },
    );
    if (!res.ok) {
      throw new Error(
        `sendMessageAsync failed: ${res.status} ${await res.text()}`,
      );
    }
  }

  async getSessionStatus(): Promise<Record<string, unknown>> {
    const res = await fetch(`${BASE_URL}/session/status`, {
      headers: authHeaders(),
    });
    if (!res.ok) return {};
    return await res.json();
  }

  async closeSession(sessionId: string): Promise<void> {
    try {
      await fetch(`${BASE_URL}/session/${sessionId}`, {
        method: "DELETE",
        headers: authHeaders(),
      });
    } catch {
      // Ignore close errors — session may already be gone
    }
  }
}
