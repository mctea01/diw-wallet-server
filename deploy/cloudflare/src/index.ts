import { Container, getContainer } from "@cloudflare/containers";

interface Env {
  DIW_SERVER: DurableObjectNamespace<DiwServer>;
  PUBLIC_URL: string;
  FEATURES: string;
  ZKTLS_TOKEN_SHA256: string;
  SLEEP_AFTER: string;
  SIGNING_KEY_PEM?: string;
  VERIFIER_KEY_PEM?: string;
  VERIFIER_ACCESS_TOKEN?: string;
}

/**
 * The server container. Its disk does not survive a restart, so keys come from secrets; generated keys would change
 * on every start and the wallet would ask the user to trust the server again.
 */
export class DiwServer extends Container<Env> {
  defaultPort = 8080;

  constructor(ctx: DurableObjectState<{}>, env: Env) {
    super(ctx, env);
    this.sleepAfter = env.SLEEP_AFTER || "20m";
    const vars: Record<string, string> = {
      DIW_PUBLIC_URL: env.PUBLIC_URL,
      DIW_FEATURES: env.FEATURES,
      DIW_LISTEN_HOST: "0.0.0.0",
      DIW_LISTEN_PORT: "8080",
      // Only the Worker below reaches the container, and it sets X-Forwarded-For from CF-Connecting-IP.
      DIW_TRUST_FORWARDED: "true",
      DIW_DATA_DIR: "/tmp/diw-server",
      DIW_SIGNING_GENERATE: "false",
      JAVA_OPTS: "-Xmx384m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m",
    };
    if (env.ZKTLS_TOKEN_SHA256) vars.DIW_ZKTLS_TOKEN_SHA256 = env.ZKTLS_TOKEN_SHA256;
    if (env.SIGNING_KEY_PEM) vars.DIW_SIGNING_KEY_PEM = env.SIGNING_KEY_PEM;
    if (env.VERIFIER_KEY_PEM) vars.DIW_VERIFIER_KEY_PEM = env.VERIFIER_KEY_PEM;
    if (env.VERIFIER_ACCESS_TOKEN) vars.DIW_VERIFIER_ACCESS_TOKEN = env.VERIFIER_ACCESS_TOKEN;
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
