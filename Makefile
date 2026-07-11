# Deploy target for the Fly app (ktor/). See fly.toml for machine config.
#
# One-time setup (in the gpipi org):
#   fly launch --no-deploy --org gpipi --region nrt --copy-config --name gpipi-bot   # from ktor/
#   fly secrets set --app gpipi-bot \
#       DATABASE_URL='jdbc:postgresql://<supabase-pooler-host>:5432/postgres' \
#       DATABASE_USER='postgres.<ref>' DATABASE_PASSWORD='<pw>' \
#       SLACK_SIGNING_SECRET='<...>' SLACK_BOT_OAUTH_TOKEN='xoxb-<...>' \
#       OPENROUTER_API_KEY='<...>'
# Then point the Slack app's Event Subscription URL at https://gpipi-bot.fly.dev/slack/events.

.PHONY: deploy deploy-local

# LOCAL=1 builds the image with the local Docker daemon and pushes it, skipping
# Fly's remote (Depot) builder — the fallback when that builder fails to provision.
FLY_DEPLOY_FLAGS = $(if $(LOCAL),--local-only)

# No schema-push step (unlike jepangpg): Flyway runs migrations in-process on boot,
# so the new machine migrates itself against DATABASE_URL as it starts.
deploy:
	cd ktor && fly deploy $(FLY_DEPLOY_FLAGS)

# Convenience alias: `make deploy-local` == `make deploy LOCAL=1`.
deploy-local: LOCAL := 1
deploy-local: deploy
