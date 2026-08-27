# syntax=docker/dockerfile:1
#
# The compile worker: claims jobs from Postgres, renders them, writes the artifact.
#
# Built from the repository root, because it depends on packages/compile through an npm
# workspace and the lockfile that pins it is up there:
#
#   docker build -f worker/Dockerfile -t noveltea-worker .
#
# No build step and no bundler. The worker is TypeScript run directly by Node, which strips
# the types on load — the same thing `npm start -w @noveltea/worker` does on a developer's
# machine, so what runs in the container is what was tested.

FROM node:24-alpine
WORKDIR /app

ENV NODE_ENV=production \
    NOVELTEA_EXPORT_PATH=/var/lib/noveltea/exports \
    NOVELTEA_STAGING_PATH=/var/lib/noveltea/staging

# Every workspace the lockfile knows about, or `npm ci` refuses: it verifies the whole
# tree against package-lock.json rather than the part being installed. Sources come
# before the install rather than after, because client-db has a `prepare` script that
# bundles its migrations, and a install-first layout leaves npm running it against a
# directory holding nothing but a package.json.
COPY package.json package-lock.json ./
COPY packages packages
COPY worker worker
RUN npm ci --omit=dev

# The API's own exports directory, shared with it: the API records where an artifact
# landed and serves it, the worker is what puts it there.
RUN mkdir -p "$NOVELTEA_EXPORT_PATH" "$NOVELTEA_STAGING_PATH" \
    && chown -R node:node /var/lib/noveltea

USER node
CMD ["node", "worker/src/index.ts"]
