# Ezdomain

本题为 Windows 域渗透题，预期解如下：

1. `25565/tcp` 为 `Minecraft 1.12.2`，入口点是 `Log4Shell`，拿到 `svc_minecraft`。
2. `svc_minecraft` 具备 `SeImpersonatePrivilege`，本地提权到 `SYSTEM`。
3. 读取 `LSA Secret`，获得 `sql_backup` 域账号凭据。
4. 使用 `sql_backup` 做 `Kerberoast`，获得 `svc_deploy` 凭据。
5. `svc_deploy` 对 `backup_svc` 有 `ForceChangePassword` 权限，重置 `backup_svc`。
6. `backup_svc` 具备 `DCSync` 权限，导出域管凭据。
7. 传递域管凭据登录域控，读取最终 flag。
