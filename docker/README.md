# docker/

`docker-compose.yml` runs a local Neo4j with the same image tag the integration tests use.

```bash
cp .env.example .env          # set NEO4J_PASSWORD
docker compose -f docker/docker-compose.yml --env-file .env up -d
```

Browser: http://localhost:7474 · Bolt: bolt://localhost:7687
