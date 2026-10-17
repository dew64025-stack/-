param([string]$Tests = 'MysqlCommunityPrivacyTest', [switch]$Combined)
$ErrorActionPreference = 'Stop'
$taskContainer = (docker inspect czt-abc-review-20261015 | ConvertFrom-Json)[0]
$taskConfigured = @($taskContainer.HostConfig.PortBindings.'3306/tcp')
if ($taskContainer.Config.Labels.'com.caizhitong.plan' -ne 'abc-review-repairs-20261015' -or
    $taskConfigured.Count -ne 1 -or $taskConfigured[0].HostIp -ne '127.0.0.1' -or
    $taskConfigured[0].HostPort -notin @('', '0')) { throw 'Owned ephemeral loopback container required' }
if (-not $taskContainer.State.Running) { throw 'Owned container must be running' }
$taskBinding = @($taskContainer.NetworkSettings.Ports.'3306/tcp')
if ($taskBinding.Count -ne 1 -or $taskBinding[0].HostIp -ne '127.0.0.1' -or
    $taskBinding[0].HostPort -notmatch '^\d{1,5}$' -or
    $taskBinding[0].HostPort -in @('3306','13306')) { throw 'Owned dynamic loopback port required' }
try {
    $taskPassword = ($taskContainer.Config.Env | Where-Object { $_ -like 'MYSQL_ROOT_PASSWORD=*' }).Substring(20)
    $env:CZT_ABC_C_VERIFIED_PORT = $taskBinding[0].HostPort
    $env:CZT_ABC_C_VERIFIED_LABEL = 'abc-review-repairs-20261015'
    $env:CZT_ABC_C_SCHEMA = 'czt_abc_c_' + [Guid]::NewGuid().ToString('N')
    $env:CZT_ABC_C_ROOT_PASSWORD = $taskPassword
    $env:CZT_ABC_C_MYSQL_TEST = 'true'
    $env:CZT_ABC_B_MYSQL_TEST = 'false'
    if ($Combined) {
        $env:CZT_ABC_B_VERIFIED_PORT = $taskBinding[0].HostPort
        $env:CZT_ABC_B_VERIFIED_LABEL = 'abc-review-repairs-20261015'
        $taskBSchema = 'czt_abc_b_' + [Guid]::NewGuid().ToString('N')
        $env:CZT_ABC_B_MYSQL_URL = 'jdbc:mysql://127.0.0.1:' + $taskBinding[0].HostPort + '/' + $taskBSchema + '?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
        $env:CZT_ABC_B_MYSQL_PASSWORD = $taskPassword
        $env:CZT_ABC_B_MYSQL_TEST = 'true'
    }
    $taskContainer = $null
    $env:CZT_MYSQL_FILES_TEST = 'false'
    $env:CZT_LIVE_FILES_TEST = 'false'
    $env:JAVA_HOME = 'C:/Users/19683/AppData/Local/caizhitong-devtools/jdk-21.0.12.1+1'
    $env:MAVEN_OPTS = '-Duser.language=en -Duser.country=US'
    & 'C:/Users/19683/AppData/Local/caizhitong-devtools/apache-maven-3.9.16/bin/mvn.cmd' -B -ntp -f backend/pom.xml "-Dtest=$Tests" test
    $taskExit = $LASTEXITCODE
} finally {
    $taskPassword = $null
    $taskContainer = $null
    foreach ($taskName in @('CZT_ABC_C_ROOT_PASSWORD','CZT_ABC_B_MYSQL_PASSWORD','CZT_ABC_C_MYSQL_TEST','CZT_ABC_B_MYSQL_TEST')) {
        [Environment]::SetEnvironmentVariable($taskName, $null, 'Process')
    }
}
exit $taskExit
