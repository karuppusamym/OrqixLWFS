# Synthetic XPDL 2.0 flow exports

These are hand-authored fixtures for the automatic `pegaXpdl` adapter, not genuine Pega exports or certification of a Pega release. They use the XPDL 2.0 namespace documented for interchange. No canonical Orqix definition or manual mapping package is supplied.

- `small.xpdl`: plain start and finish.
- `medium.xpdl`: XOR condition `amount >= 5000` and OTHERWISE, with Finance/Standard finishes.
- `review-branch.xpdl`: a literal Managers performer, explicit approval/rejection routes, then amount-based routing. Map `Managers` to `managers` and `review` approval paths to `approvedPath`/`rejectedPath`.

Upload as **Pega flow export (XPDL 2.0)**, review metadata exclusions and group/approval mappings, convert to a draft, then publish separately. The backend infers the simple `amount` request field from the comparison. A supplied request schema can refine its constraints.

Unsupported source attributes, executable extensions, activities/tools, subprocesses, computed assignments, non-XOR routes and triggered events block conversion. Native Rule-Obj-* XML pages and RAP archives are not XPDL and are not automatically interpreted.

See [S46](../../../docs/build/S46-pega-xpdl.md) for scope, tests and source references.
