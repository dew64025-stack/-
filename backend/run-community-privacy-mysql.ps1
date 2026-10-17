param([string]$Tests = 'MysqlCommunityPrivacyTest')
$ErrorActionPreference = 'Stop'
$taskContainer = (docker inspect czt-abc-review-20261015 | ConvertFrom-Json)[0]
$taskConfigured = @($taskContainer.HostConfig.PortBindings.'3306/tcp')
if ($taskContainer.Config.Labels.'com.caizhitong.plan' -ne 'abc-review-repairs-20261015' -or
    $taskConfigured.Count -ne 1 -or $taskConfigured[0].HostIp -ne '127.0.0.1' -or
    $taskConfigured[0].HostPort -notin @('', '0')) { throw 'Owned ephemeral loopback container required' }
if (-not $taskContainer.State.Running) {
    docker start czt-abc-review-20261015 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Owned container could not be resumed' }
    $taskContainer = (docker inspect czt-abc-review-20261015 | ConvertFrom-Json)[0]
}
$taskBinding = @($taskContainer.NetworkSettings.Ports.'3306/tcp')
if ($taskContainer.Config.Labels.'com.caizhitong.plan' -ne 'abc-review-repairs-20261015' -or
    -not $taskContainer.State.Running -or $taskBinding.Count -ne 1 -or $taskBinding[0].HostIp -ne '127.0.0.1' -or
    $taskBinding[0].HostPort -in @('3306','13306')) { throw 'Owned loopback container required' }
try {
    $env:CZT_ABC_C_VERIFIED_PORT = $taskBinding[0].HostPort
    $env:CZT_ABC_C_VERIFIED_LABEL = 'abc-review-repairs-20261015'
    $env:CZT_ABC_C_SCHEMA = 'czt_abc_c_' + [Guid]::NewGuid().ToString('N')
    $env:CZT_ABC_C_ROOT_PASSWORD = ($taskContainer.Config.Env | Where-Object { $_ -like 'MYSQL_ROOT_PASSWORD=*' }).Substring(20)
    $taskContainer = $null
    $env:CZT_ABC_C_MYSQL_TEST = 'true'
    $env:CZT_MYSQL_FILES_TEST = 'false'
    $env:CZT_LIVE_FILES_TEST = 'false'
    $env:JAVA_HOME = 'C:/Users/19683/AppData/Local/caizhitong-devtools/jdk-21.0.12.1+1'
    $env:MAVEN_OPTS = '-Duser.language=en -Duser.country=US'
    & 'C:/Users/19683/AppData/Local/caizhitong-devtools/apache-maven-3.9.16/bin/mvn.cmd' -B -ntp -f backend/pom.xml "-Dtest=$Tests" test
    $taskExit = $LASTEXITCODE
} finally {
    $env:CZT_ABC_C_ROOT_PASSWORD = $null
    $taskContainer = $null
    $env:CZT_ABC_C_MYSQL_TEST = $null
}
exit $taskExit
