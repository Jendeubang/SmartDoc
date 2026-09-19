# SmartDoc privacy and AI processing baseline

SmartDoc can send document text, prompts and recordings to configured external AI
providers, including DeepSeek and DashScope. A production operator must disclose
this processing before users upload data and must not enable external processing
for a tenant without an appropriate legal basis and agreement.

## Production requirements

- Publish a privacy notice naming every processor, processing region, purpose and
  retention period.
- Obtain the required enterprise/customer consent and data-processing agreements.
- Provide per-organization controls to disable external AI processing.
- Define retention and deletion periods for source files, parsed text, vectors,
  conversations, task history, audit records and backups.
- Never use customer documents for model training unless the customer separately
  opts in in writing.
- Treat model output as generated assistance. Require human review for legal,
  financial, HR, safety or other high-impact decisions.
- Provide data export, deletion and security-incident contact procedures.

## Manual fields before release

- Legal entity and contact: **MANUAL INPUT REQUIRED**
- Hosting region: **MANUAL INPUT REQUIRED**
- DeepSeek account/terms reviewed on: **MANUAL INPUT REQUIRED**
- DashScope account/terms reviewed on: **MANUAL INPUT REQUIRED**
- Default retention periods: **MANUAL INPUT REQUIRED**
- Incident response contact: **MANUAL INPUT REQUIRED**
