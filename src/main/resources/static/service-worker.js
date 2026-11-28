"use strict";

const CACHE_PREFIX = "urutau-jigs-pwa-";
const CACHE_NAME = "urutau-jigs-pwa-0.2.5";
const OFFLINE_PAGE = "/offline.html";
const PUBLIC_TYPES = new Map([
  [OFFLINE_PAGE, ["text/html"]],
  ["/manifest.webmanifest", ["application/manifest+json"]],
  ["/pwa.js", ["text/javascript", "application/javascript"]],
  ["/app.css", ["text/css"]],
  ["/login.js", ["text/javascript", "application/javascript"]],
  ["/icons.svg", ["image/svg+xml"]],
  ["/assets/urutau-logo.png", ["image/png"]],
  ["/icons/icon-192.png", ["image/png"]],
  ["/icons/icon-512.png", ["image/png"]],
  ["/icons/icon-maskable-512.png", ["image/png"]],
  ["/icons/apple-touch-icon.png", ["image/png"]],
  ["/icons/favicon.ico", ["image/x-icon", "image/vnd.microsoft.icon"]]
]);
const APP_NAVIGATIONS = new Set(["/", "/index.html", "/login.html", OFFLINE_PAGE]);
const UNAVAILABLE_STATUSES = new Set([502, 503, 504]);

function publicUrl(path) {
  return new URL(path, self.location.origin).href;
}

function fetchPublicAsset(path) {
  return fetch(publicUrl(path), { credentials: "omit", cache: "no-store", redirect: "error" });
}

function isPublicAsset(path, response) {
  const mediaType = response.headers.get("Content-Type")?.split(";")[0].trim().toLowerCase();
  return response.status === 200 && response.url === publicUrl(path) && PUBLIC_TYPES.get(path).includes(mediaType);
}

async function installPublicCache() {
  const cache = await caches.open(CACHE_NAME);
  for (const path of PUBLIC_TYPES.keys()) {
    const response = await fetchPublicAsset(path);
    if (!isPublicAsset(path, response)) throw new Error(`Recurso público inválido: ${path}`);
    await cache.put(publicUrl(path), response);
  }
}

self.addEventListener("install", event => {
  event.waitUntil(installPublicCache());
});

self.addEventListener("activate", event => {
  event.waitUntil((async () => {
    const obsolete = (await caches.keys()).filter(name => name.startsWith(CACHE_PREFIX) && name !== CACHE_NAME);
    await Promise.all(obsolete.map(name => caches.delete(name)));
    await self.clients.claim();
  })());
});

self.addEventListener("message", event => {
  if (event.data?.type === "SKIP_WAITING" && event.source?.type === "window") {
    event.waitUntil(self.skipWaiting());
  }
});

async function cachedPublicAsset(path) {
  const cache = await caches.open(CACHE_NAME);
  return cache.match(publicUrl(path));
}

async function publicAssetFromNetwork(path) {
  let response;
  try {
    response = await fetchPublicAsset(path);
    if (isPublicAsset(path, response)) return response;
    if (!response.ok && !UNAVAILABLE_STATUSES.has(response.status)) return response;
  } catch {
    // The previously validated public resource can still be used while offline.
  }
  return (await cachedPublicAsset(path)) ?? Response.error();
}

async function navigateFromNetwork(request) {
  // Always send the original navigation with its session and redirect semantics.
  // Only the public offline screen is eligible as a fallback, never an application page.
  try {
    const response = await fetch(request);
    if (!UNAVAILABLE_STATUSES.has(response.status)) return response;
  } catch {
    // A failed network request uses the same offline screen as a failed gateway.
  }
  return (await cachedPublicAsset(OFFLINE_PAGE)) ?? new Response(
    "Você está sem conexão.\n\nConecte-se à internet para continuar utilizando o URUTAU JIGS.",
    {
      status: 503,
      headers: { "Content-Type": "text/plain; charset=UTF-8", "Cache-Control": "no-store" }
    }
  );
}

self.addEventListener("fetch", event => {
  const { request } = event;
  const url = new URL(request.url);
  if (request.method !== "GET" || url.origin !== self.location.origin) return;
  if (request.mode === "navigate" && APP_NAVIGATIONS.has(url.pathname)) {
    event.respondWith(navigateFromNetwork(request));
  } else if (!url.search && PUBLIC_TYPES.has(url.pathname)) {
    event.respondWith(publicAssetFromNetwork(url.pathname));
  }
});
