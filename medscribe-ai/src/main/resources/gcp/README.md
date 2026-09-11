# GCP Service Account Credentials

This directory contains Google Cloud Platform service account credentials for MedScribe AI.

## Current Configuration

- **Project ID**: `<YOUR_GCP_PROJECT>`
- **Service Account**: `medscribe-ai@<YOUR_GCP_PROJECT>.iam.gserviceaccount.com`
- **Credentials File**: `gcp-credentials.json`

## Security Warning

⚠️ **IMPORTANT**: These files contain sensitive credentials!

- **NEVER commit these files to Git** (already in .gitignore)
- **NEVER share these files publicly**
- **NEVER upload to public repositories**
- Store securely and rotate keys periodically

## Usage

The application automatically loads credentials from this location when using the GCP profile:

```bash
# Run with GCP Vision API
./gradlew bootRun --args='--spring.profiles.active=gcp'
```

## Configuration

Configured in `application-gcp.properties`:

```properties
gcp.project-id=<YOUR_GCP_PROJECT>
gcp.credentials-path=classpath:gcp/gcp-credentials.json
```

## Service Account Permissions

The service account has the following roles:
- **Cloud Vision API User** (`roles/cloudvision.user`)

## Rotating Credentials

To rotate service account keys:

1. Create a new key in GCP Console
2. Download the new JSON file to this directory
3. Update `application-gcp.properties` with the new filename
4. Test the application
5. Delete the old key from GCP Console

## For Production Deployment

**DO NOT** include this file in Docker images or Kubernetes deployments. Instead:

### Docker
Mount as volume:
```bash
docker run -v /secure/path/to/credentials.json:/etc/gcp/service-account.json \
  -e GCP_CREDENTIALS_PATH=/etc/gcp/service-account.json \
  medscribe-ai:latest
```

### Kubernetes
Use secrets:
```bash
kubectl create secret generic gcp-credentials \
  --from-file=service-account.json=./gcp-credentials.json
```

### Google Cloud Run
Use Application Default Credentials (no file needed):
```bash
gcloud run deploy medscribe-ai \
  --service-account medscribe-ai@<YOUR_GCP_PROJECT>.iam.gserviceaccount.com
```

## References

- [GCP Quickstart Guide](../../../../docs/GCP_QUICKSTART_GUIDE.md)
- [GCP Vision Integration](../../../../docs/GCP_VISION_INTEGRATION.md)
