# AWS resources for Baymax (DR-5)

- `baymax-s3-policy.json` — the only policy the EC2 instance role needs: object read/write/delete plus list, scoped to the `elioo-baymax-prod` bucket.
- `baymax-ec2-role-trust.json` — trust policy for that role (EC2 service).

Bucket: `elioo-baymax-prod`, ap-south-1, block all public access on, default encryption SSE-S3, versioning off.
The role is attached to the pilot instance; the app reads it through the instance metadata service
(`baymax.storage.credentials=instance-role`), so no static key for S3 exists anywhere.
