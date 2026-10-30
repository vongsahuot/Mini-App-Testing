import type { NextConfig } from "next";

// Rewrites are evaluated at build time for `output: "standalone"`, so the Docker image
// must set these env vars before `next build` (see Dockerfile).
const PORTAL_URL = process.env.PORTAL_URL ?? "http://localhost:8081";
const BANK_URL = process.env.BANK_URL ?? "http://localhost:8082";
const PARTNER_URL = process.env.PARTNER_URL ?? "http://localhost:8083";

const nextConfig: NextConfig = {
  output: "standalone",
  eslint: { ignoreDuringBuilds: true },
  async rewrites() {
    return [
      { source: "/portal-api/:path*", destination: `${PORTAL_URL}/:path*` },
      { source: "/bank-api/:path*", destination: `${BANK_URL}/:path*` },
      { source: "/partner-api/:path*", destination: `${PARTNER_URL}/:path*` },
    ];
  },
};

export default nextConfig;
