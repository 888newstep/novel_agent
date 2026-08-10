# Dependency Security Baseline

Updated: 2026-08-10

## Why This Exists

`novel_agent` depends on the Milvus Java SDK, which brings a relatively large transitive dependency graph. A GitHub push reported a high-severity dependency alert, so the repository now treats dependency versions as an explicit engineering concern instead of relying only on the framework BOM.

## Current Remediation

The project keeps `milvus-sdk-java` at `2.4.11` to avoid an unplanned SDK API migration, and overrides vulnerable transitive components through Maven `dependencyManagement` in `pom.xml`:

| Component | Security baseline | Why it is explicit |
|-----------|------------------|--------------------|
| `commons-beanutils` | `1.11.0` | fixes the old `1.9.4` transitive version |
| `commons-io` | `2.14.0` | fixes the old `2.8.0` transitive version |
| `commons-compress` | `1.26.0` | fixes the old `1.24.0` transitive version |
| `commons-configuration2` | `2.15.0` | fixes the old `2.8.0` transitive version |
| `grpc-netty-shaded` | `1.75.0` | fixes the old `1.59.1` transitive version |
| `mysql-connector-j` | `8.2.0` | replaces the explicitly pinned `8.0.33` version |

The overrides are intentionally centralized so a dependency-tree review can answer three questions quickly: which version is resolved, why it differs from the SDK's request, and which upgrade boundary must be regression-tested.

## Verification Commands

```powershell
mvn dependency:tree -Dscope=runtime -DoutputFile=target/dependency-tree.txt
mvn test -DskipITs -B
```

The first command verifies the resolved versions. The second command protects Milvus search, import, controller, and cost-governance behavior after dependency changes.

## Automation

- `.github/dependabot.yml` checks Maven dependencies weekly and GitHub Actions monthly.
- `.github/workflows/ci.yml` runs `actions/dependency-review-action` on pull requests.
- `SECURITY.md` remains the reporting path for vulnerabilities that require private coordination.

## Upgrade Policy

1. Prefer a security-fixed patch or minor version with the smallest compatible surface.
2. Run the dependency tree check before changing application code.
3. Run the full Maven test baseline before opening a pull request.
4. Upgrade the Milvus SDK separately when its API or transport stack needs to move as a unit.

This document records version policy and verification evidence; it does not replace GitHub Dependabot, provider advisories, or a deployment-specific security review.
