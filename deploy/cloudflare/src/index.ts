import { Container, getContainer } from "@cloudflare/containers";

interface Env {
  DIW_SERVER: DurableObjectNamespace<DiwServer>;
  /** The server.conf every other deployment uses, stored whole as a secret: wrangler secret put SERVER_CONF < server.conf */
  SERVER_CONF?: string;
  SLEEP_AFTER?: string;
  JAVA_OPTS?: string;
  /** Any DIW_<KEY> var or secret is passed through unchanged and overrides the file, as an environment variable does elsewhere. */
  [name: string]: unknown;
}

/**
 * The server container. Its disk does not survive a restart, so keys come from the settings secret; generated keys
 * would change on every start and the wallet would ask the user to trust the server again.
 */
export class DiwServer extends Container<Env> {
  defaultPort = 8080;

  constructor(ctx: DurableObjectState<{}>, env: Env) {
    super(ctx, env);
    this.sleepAfter = env.SLEEP_AFTER || "20m";
    const vars: Record<string, string> = {
      // Only defaults change with the platform: listen on 0.0.0.0, trust the X-Forwarded-For set below, keep data
      // in /tmp/diw-server and never generate keys there.
      DIW_PLATFORM: "cloudflare",
      DIW_LISTEN_PORT: "8080",
      JAVA_OPTS: env.JAVA_OPTS || "-Xmx384m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m",
    };
    if (typeof env.SERVER_CONF === "string" && env.SERVER_CONF.trim()) vars.DIW_CONFIG_TEXT = env.SERVER_CONF;
    for (const [name, value] of Object.entries(env)) {
      if (name.startsWith("DIW_") && typeof value === "string" && name !== "DIW_SERVER") vars[name] = value;
    }
    this.envVars = vars;
  }
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const headers = new Headers(request.headers);
    const client = request.headers.get("CF-Connecting-IP");
    // Replace, never append: a client could otherwise choose the address its per-address limits are counted on.
    if (client) headers.set("X-Forwarded-For", client);
    else headers.delete("X-Forwarded-For");
    headers.set("X-Forwarded-Proto", "https");
    // One named instance for everything, with max_instances 1 in wrangler.jsonc.
    return getContainer(env.DIW_SERVER, "main").fetch(new Request(request, { headers }));
  },
};
