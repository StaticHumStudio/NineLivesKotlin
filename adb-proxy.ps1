# Simple TCP proxy for ADB over Tailscale
$localPort = 5555
$remoteHost = "100.125.110.103"
$remotePort = 39267

$listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, $localPort)
$listener.Start()

Write-Host "ADB Proxy listening on localhost:$localPort"
Write-Host "Forwarding to $remoteHost:$remotePort"
Write-Host "Press Ctrl+C to stop"

try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        Write-Host "Client connected from $($client.Client.RemoteEndPoint)"

        # Start a new job to handle this connection
        Start-Job -ScriptBlock {
            param($client, $remoteHost, $remotePort)

            try {
                $remoteClient = New-Object System.Net.Sockets.TcpClient($remoteHost, $remotePort)
                $clientStream = $client.GetStream()
                $remoteStream = $remoteClient.GetStream()

                $buffer = New-Object byte[] 8192

                # Bidirectional relay
                while ($client.Connected -and $remoteClient.Connected) {
                    if ($clientStream.DataAvailable) {
                        $read = $clientStream.Read($buffer, 0, $buffer.Length)
                        $remoteStream.Write($buffer, 0, $read)
                        $remoteStream.Flush()
                    }
                    if ($remoteStream.DataAvailable) {
                        $read = $remoteStream.Read($buffer, 0, $buffer.Length)
                        $clientStream.Write($buffer, 0, $read)
                        $clientStream.Flush()
                    }
                    Start-Sleep -Milliseconds 10
                }
            } catch {
                Write-Host "Connection error: $_"
            } finally {
                if ($null -ne $client) { $client.Close() }
                if ($null -ne $remoteClient) { $remoteClient.Close() }
            }
        } -ArgumentList $client, $remoteHost, $remotePort
    }
} finally {
    $listener.Stop()
}
