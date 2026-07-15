# E2E testing (instrumented)

Canonical skill: **[Work/skills/android-instrumented-e2e](../../../../skills/android-instrumented-e2e/SKILL.md)**

## Run PRISM AI workflows locally

**Default: emulator** (auto-starts an ARM64 AVD from config if none is running).

```powershell
cd Work\skills\android-instrumented-e2e
.\scripts\e2e-run.ps1 -Config apps\prismai.e2e.json -Suite ui
```

Physical device override:

```powershell
.\scripts\e2e-run.ps1 -Config apps\prismai.e2e.json -Suite ui -PreferPhysical -DeviceSerial <serial>
```

## Workflow coverage (`VisionWorkflowE2ETest`)

| Workflow | Test method |
|----------|-------------|
| Launch | `workflow_launch_mainControlsVisible` |
| Night vision | `workflow_nightVision_toggleAndSlider` |
| IMG ED / COL / HD | `workflow_img_*` |
| ROB OF / SEG / DF | `workflow_rob_*` |
| Text translation | `workflow_textTranslation` |
| Sphere | `workflow_sphere_enterAndExit` |
| Info panel | `workflow_infoPanel_toggle` |
| Gallery | `workflow_gallery_opensSavedMedia` |
| Full walkthrough | `allPrimaryWorkflows_sequentialWalkthrough` |

Config: [e2e/prismai.e2e.json](../../../e2e/prismai.e2e.json)
