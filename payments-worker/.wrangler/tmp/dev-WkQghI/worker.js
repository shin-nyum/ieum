var __defProp = Object.defineProperty;
var __name = (target, value) => __defProp(target, "name", { value, configurable: true });

// worker.js
var ALLOW_ORIGIN = "https://shin-nyum.github.io";
var AMOUNT = 4900;
var CORS = {
  "Access-Control-Allow-Origin": ALLOW_ORIGIN,
  "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type"
};
function json(obj, status) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "Content-Type": "application/json", ...CORS }
  });
}
__name(json, "json");
async function tossFetch(path, opts, secret) {
  const r = await fetch("https://api.tosspayments.com" + path, {
    ...opts,
    headers: { Authorization: "Basic " + btoa(secret + ":"), "Content-Type": "application/json", ...opts.headers || {} }
  });
  const text = await r.text();
  let body = null;
  try {
    body = JSON.parse(text);
  } catch (e) {
  }
  return { status: r.status, ok: r.ok, body };
}
__name(tossFetch, "tossFetch");
var worker_default = {
  async fetch(req, env) {
    try {
      if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
      const url = new URL(req.url);
      if (!env.TOSS_SECRET_KEY) return json({ ok: false, message: "server not configured" }, 500);
      if (req.method === "POST" && url.pathname === "/confirm") {
        let b;
        try {
          b = await req.json();
        } catch (e) {
          return json({ ok: false, final: true, message: "bad json" }, 400);
        }
        const { paymentKey, orderId } = b || {};
        if (!paymentKey || !orderId || !/^ieum-/.test(orderId)) return json({ ok: false, final: true, message: "invalid order" }, 400);
        const r = await tossFetch("/v1/payments/confirm", { method: "POST", body: JSON.stringify({ paymentKey, orderId, amount: AMOUNT }) }, env.TOSS_SECRET_KEY);
        if (r.body === null) return json({ ok: false, retry: true, message: "gateway error" }, 502);
        if (!r.ok) {
          if (r.body.code === "ALREADY_PROCESSED_PAYMENT") return json({ ok: true, paid: true, receiptUrl: null }, 200);
          return json({ ok: false, final: true, code: r.body.code, message: r.body.message || "declined" }, 402);
        }
        return json({ ok: true, paid: true, orderId: r.body.orderId, approvedAt: r.body.approvedAt, receiptUrl: r.body.receipt && r.body.receipt.url || null }, 200);
      }
      if (req.method === "GET" && url.pathname === "/recover") {
        const orderId = url.searchParams.get("orderId") || "";
        if (!/^ieum-/.test(orderId)) return json({ ok: false, final: true, message: "invalid order" }, 400);
        const q = await tossFetch("/v1/payments/orders/" + encodeURIComponent(orderId), { method: "GET" }, env.TOSS_SECRET_KEY);
        if (q.body === null) return json({ ok: false, retry: true, message: "gateway error" }, 502);
        if (q.status === 404) return json({ ok: true, paid: false, final: true, status: "NOT_FOUND" }, 200);
        if (!q.ok) return json({ ok: false, retry: true, message: q.body.message || "query failed" }, 502);
        const st = q.body.status;
        if (st === "DONE") return json({ ok: true, paid: true, receiptUrl: q.body.receipt && q.body.receipt.url || null }, 200);
        if ((st === "READY" || st === "IN_PROGRESS") && q.body.paymentKey) {
          const c = await tossFetch("/v1/payments/confirm", { method: "POST", body: JSON.stringify({ paymentKey: q.body.paymentKey, orderId, amount: AMOUNT }) }, env.TOSS_SECRET_KEY);
          if (c.body === null) return json({ ok: false, retry: true, message: "gateway error" }, 502);
          if (c.ok) return json({ ok: true, paid: true, receiptUrl: c.body.receipt && c.body.receipt.url || null }, 200);
          if (c.body.code === "ALREADY_PROCESSED_PAYMENT") return json({ ok: true, paid: true, receiptUrl: null }, 200);
          return json({ ok: true, paid: false, final: true, status: st, message: c.body.message }, 200);
        }
        const finalStates = ["CANCELED", "PARTIAL_CANCELED", "ABORTED", "EXPIRED"];
        return json({ ok: true, paid: false, final: finalStates.includes(st), status: st }, 200);
      }
      return json({ ok: false, message: "ieum payments worker" }, 200);
    } catch (e) {
      return json({ ok: false, retry: true, message: "server error" }, 500);
    }
  }
};

// ../../AppData/Local/npm-cache/_npx/d77349f55c2be1c0/node_modules/wrangler/templates/middleware/middleware-ensure-req-body-drained.ts
var drainBody = /* @__PURE__ */ __name(async (request, env, _ctx, middlewareCtx) => {
  try {
    return await middlewareCtx.next(request, env);
  } finally {
    try {
      if (request.body !== null && !request.bodyUsed) {
        const reader = request.body.getReader();
        while (!(await reader.read()).done) {
        }
      }
    } catch (e) {
      console.error("Failed to drain the unused request body.", e);
    }
  }
}, "drainBody");
var middleware_ensure_req_body_drained_default = drainBody;

// ../../AppData/Local/npm-cache/_npx/d77349f55c2be1c0/node_modules/wrangler/templates/middleware/middleware-miniflare3-json-error.ts
function reduceError(e) {
  return {
    name: e?.name,
    message: e?.message ?? String(e),
    stack: e?.stack,
    cause: e?.cause === void 0 ? void 0 : reduceError(e.cause)
  };
}
__name(reduceError, "reduceError");
var jsonError = /* @__PURE__ */ __name(async (request, env, _ctx, middlewareCtx) => {
  try {
    return await middlewareCtx.next(request, env);
  } catch (e) {
    const error = reduceError(e);
    return Response.json(error, {
      status: 500,
      headers: { "MF-Experimental-Error-Stack": "true" }
    });
  }
}, "jsonError");
var middleware_miniflare3_json_error_default = jsonError;

// .wrangler/tmp/bundle-083VIz/middleware-insertion-facade.js
var __INTERNAL_WRANGLER_MIDDLEWARE__ = [
  middleware_ensure_req_body_drained_default,
  middleware_miniflare3_json_error_default
];
var middleware_insertion_facade_default = worker_default;

// ../../AppData/Local/npm-cache/_npx/d77349f55c2be1c0/node_modules/wrangler/templates/middleware/common.ts
var __facade_middleware__ = [];
function __facade_register__(...args) {
  __facade_middleware__.push(...args.flat());
}
__name(__facade_register__, "__facade_register__");
function __facade_invokeChain__(request, env, ctx, dispatch, middlewareChain) {
  const [head, ...tail] = middlewareChain;
  const middlewareCtx = {
    dispatch,
    next(newRequest, newEnv) {
      return __facade_invokeChain__(newRequest, newEnv, ctx, dispatch, tail);
    }
  };
  return head(request, env, ctx, middlewareCtx);
}
__name(__facade_invokeChain__, "__facade_invokeChain__");
function __facade_invoke__(request, env, ctx, dispatch, finalMiddleware) {
  return __facade_invokeChain__(request, env, ctx, dispatch, [
    ...__facade_middleware__,
    finalMiddleware
  ]);
}
__name(__facade_invoke__, "__facade_invoke__");

// .wrangler/tmp/bundle-083VIz/middleware-loader.entry.ts
var __Facade_ScheduledController__ = class ___Facade_ScheduledController__ {
  constructor(scheduledTime, cron, noRetry) {
    this.scheduledTime = scheduledTime;
    this.cron = cron;
    this.#noRetry = noRetry;
  }
  scheduledTime;
  cron;
  static {
    __name(this, "__Facade_ScheduledController__");
  }
  #noRetry;
  noRetry() {
    if (!(this instanceof ___Facade_ScheduledController__)) {
      throw new TypeError("Illegal invocation");
    }
    this.#noRetry();
  }
};
function wrapExportedHandler(worker) {
  if (__INTERNAL_WRANGLER_MIDDLEWARE__ === void 0 || __INTERNAL_WRANGLER_MIDDLEWARE__.length === 0) {
    return worker;
  }
  for (const middleware of __INTERNAL_WRANGLER_MIDDLEWARE__) {
    __facade_register__(middleware);
  }
  const fetchDispatcher = /* @__PURE__ */ __name(function(request, env, ctx) {
    if (worker.fetch === void 0) {
      throw new Error("Handler does not export a fetch() function.");
    }
    return worker.fetch(request, env, ctx);
  }, "fetchDispatcher");
  return {
    ...worker,
    fetch(request, env, ctx) {
      const dispatcher = /* @__PURE__ */ __name(function(type, init) {
        if (type === "scheduled" && worker.scheduled !== void 0) {
          const controller = new __Facade_ScheduledController__(
            Date.now(),
            init.cron ?? "",
            () => {
            }
          );
          return worker.scheduled(controller, env, ctx);
        }
      }, "dispatcher");
      return __facade_invoke__(request, env, ctx, dispatcher, fetchDispatcher);
    }
  };
}
__name(wrapExportedHandler, "wrapExportedHandler");
function wrapWorkerEntrypoint(klass) {
  if (__INTERNAL_WRANGLER_MIDDLEWARE__ === void 0 || __INTERNAL_WRANGLER_MIDDLEWARE__.length === 0) {
    return klass;
  }
  for (const middleware of __INTERNAL_WRANGLER_MIDDLEWARE__) {
    __facade_register__(middleware);
  }
  return class extends klass {
    #fetchDispatcher = /* @__PURE__ */ __name((request, env, ctx) => {
      this.env = env;
      this.ctx = ctx;
      if (super.fetch === void 0) {
        throw new Error("Entrypoint class does not define a fetch() function.");
      }
      return super.fetch(request);
    }, "#fetchDispatcher");
    #dispatcher = /* @__PURE__ */ __name((type, init) => {
      if (type === "scheduled" && super.scheduled !== void 0) {
        const controller = new __Facade_ScheduledController__(
          Date.now(),
          init.cron ?? "",
          () => {
          }
        );
        return super.scheduled(controller);
      }
    }, "#dispatcher");
    fetch(request) {
      return __facade_invoke__(
        request,
        this.env,
        this.ctx,
        this.#dispatcher,
        this.#fetchDispatcher
      );
    }
  };
}
__name(wrapWorkerEntrypoint, "wrapWorkerEntrypoint");
var WRAPPED_ENTRY;
if (typeof middleware_insertion_facade_default === "object") {
  WRAPPED_ENTRY = wrapExportedHandler(middleware_insertion_facade_default);
} else if (typeof middleware_insertion_facade_default === "function") {
  WRAPPED_ENTRY = wrapWorkerEntrypoint(middleware_insertion_facade_default);
}
var middleware_loader_entry_default = WRAPPED_ENTRY;
export {
  __INTERNAL_WRANGLER_MIDDLEWARE__,
  middleware_loader_entry_default as default
};
//# sourceMappingURL=worker.js.map
