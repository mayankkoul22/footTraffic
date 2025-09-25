package com.datafy.foottraffic.web

import android.content.Context
import com.datafy.foottraffic.analysis.TrafficAnalyzer
import com.datafy.foottraffic.data.AnalyticsRepository
import com.datafy.foottraffic.data.ConfigManager
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.util.*

class WebServer(
    private val context: Context,
    private val trafficAnalyzer: TrafficAnalyzer,
    port: Int = 8080
) : NanoHTTPD(port) {

    private val gson = Gson()
    private val configManager = ConfigManager(context)
    private val analyticsRepository = AnalyticsRepository(context)

    init {
        Timber.d("Web server initialized on port $port")
    }

    override fun serve(session: IHTTPSession): Response {
        // Enable CORS
        val response = when {
            session.uri == "/" -> serveMainPage()
            session.uri == "/api/analytics" -> serveAnalytics()
            session.uri == "/api/zones" -> handleZones(session)
            session.uri == "/api/settings" -> handleSettings(session)
            session.uri == "/api/reset" -> handleReset()
            session.uri == "/api/export" -> handleExport(session)
            session.uri.startsWith("/static/") -> serveStatic(session.uri)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }

        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")

        return response
    }

    private fun serveMainPage(): Response {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Foot Traffic Counter</title>
                <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body { font-family: 'Segoe UI', Tahoma, sans-serif; background: #1a1a2e; color: #eee; }
                    .container { max-width: 1400px; margin: 0 auto; padding: 20px; }
                    .header { background: #16213e; padding: 20px; border-radius: 10px; margin-bottom: 20px; }
                    .header h1 { font-size: 28px; margin-bottom: 10px; }
                    .status { display: inline-block; padding: 5px 15px; background: #22c55e; border-radius: 20px; font-size: 14px; }
                    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 20px; }
                    .card { background: #0f3460; padding: 20px; border-radius: 10px; box-shadow: 0 4px 6px rgba(0,0,0,0.3); }
                    .card h2 { font-size: 18px; margin-bottom: 15px; color: #60a5fa; }
                    .metric { display: flex; justify-content: space-between; align-items: center; margin: 10px 0; padding: 10px; background: #16213e; border-radius: 5px; }
                    .metric-value { font-size: 24px; font-weight: bold; color: #fbbf24; }
                    .metric-label { font-size: 14px; color: #9ca3af; }
                    .chart-container { height: 300px; margin-top: 20px; }
                    .zone-list { list-style: none; }
                    .zone-item { background: #16213e; padding: 10px; margin: 5px 0; border-radius: 5px; display: flex; justify-content: space-between; }
                    .zone-occupancy { display: inline-block; padding: 2px 8px; background: #22c55e; border-radius: 3px; font-size: 12px; }
                    .controls { display: flex; gap: 10px; margin-top: 20px; }
                    .btn { padding: 10px 20px; background: #3b82f6; color: white; border: none; border-radius: 5px; cursor: pointer; font-size: 14px; transition: background 0.3s; }
                    .btn:hover { background: #2563eb; }
                    .btn-danger { background: #ef4444; }
                    .btn-danger:hover { background: #dc2626; }
                    .progress-bar { width: 100%; height: 20px; background: #16213e; border-radius: 10px; overflow: hidden; margin: 10px 0; }
                    .progress-fill { height: 100%; background: linear-gradient(90deg, #22c55e, #10b981); transition: width 0.3s; }
                    #canvas { max-width: 100%; height: auto; border-radius: 10px; margin-top: 10px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>🎥 Foot Traffic Counter Dashboard</h1>
                        <span class="status">● Active</span>
                        <span style="float: right; color: #9ca3af;">Last Update: <span id="lastUpdate">-</span></span>
                    </div>
                    
                    <div class="grid">
                        <div class="card">
                            <h2>📊 Current Metrics</h2>
                            <div class="metric">
                                <span class="metric-label">Current Count</span>
                                <span class="metric-value" id="currentCount">0</span>
                            </div>
                            <div class="metric">
                                <span class="metric-label">Total Entries</span>
                                <span class="metric-value" id="totalEntries">0</span>
                            </div>
                            <div class="metric">
                                <span class="metric-label">Total Exits</span>
                                <span class="metric-value" id="totalExits">0</span>
                            </div>
                            <div class="metric">
                                <span class="metric-label">Unique Visitors</span>
                                <span class="metric-value" id="uniqueVisitors">0</span>
                            </div>
                            <div class="metric">
                                <span class="metric-label">Processing FPS</span>
                                <span class="metric-value" id="fps">0</span>
                            </div>
                        </div>
                        
                        <div class="card">
                            <h2>🗺️ Zone Status</h2>
                            <ul class="zone-list" id="zoneList">
                                <li class="zone-item">No zones configured</li>
                            </ul>
                        </div>
                        
                        <div class="card">
                            <h2>📈 Hourly Traffic</h2>
                            <canvas id="chart" class="chart-container"></canvas>
                        </div>
                        
                        <div class="card">
                            <h2>⚙️ Controls</h2>
                            <div class="controls">
                                <button class="btn" onclick="exportData()">Export CSV</button>
                                <button class="btn btn-danger" onclick="resetCounters()">Reset</button>
                            </div>
                        </div>
                    </div>
                </div>
                
                <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
                <script>
                    // Initialize chart
                    const ctx = document.getElementById('chart').getContext('2d');
                    const chart = new Chart(ctx, {
                        type: 'line',
                        data: {
                            labels: [],
                            datasets: [{
                                label: 'People Count',
                                data: [],
                                borderColor: '#3b82f6',
                                backgroundColor: 'rgba(59, 130, 246, 0.1)',
                                tension: 0.4
                            }]
                        },
                        options: {
                            responsive: true,
                            maintainAspectRatio: false,
                            plugins: {
                                legend: { display: false }
                            },
                            scales: {
                                y: { beginAtZero: true, grid: { color: '#374151' } },
                                x: { grid: { color: '#374151' } }
                            }
                        }
                    });
                    
                    // Update data every second
                    async function updateData() {
                        try {
                            const response = await fetch('/api/analytics');
                            const data = await response.json();
                            
                            document.getElementById('currentCount').textContent = data.currentCount;
                            document.getElementById('totalEntries').textContent = data.totalEntries;
                            document.getElementById('totalExits').textContent = data.totalExits;
                            document.getElementById('uniqueVisitors').textContent = data.uniqueVisitors;
                            document.getElementById('fps').textContent = data.fps.toFixed(1);
                            document.getElementById('lastUpdate').textContent = new Date().toLocaleTimeString();
                            
                            // Update zones
                            const zoneList = document.getElementById('zoneList');
                            if (data.zones && Object.keys(data.zones).length > 0) {
                                zoneList.innerHTML = Object.entries(data.zones).map(([id, zone]) => `
                                    <li class="zone-item">
                                        <span>${"$"}{zone.name}</span>
                                        <span class="zone-occupancy">${"$"}{zone.count}/${"$"}{zone.capacity}</span>
                                    </li>
                                `).join('');
                            }
                            
                            // Update chart
                            const now = new Date();
                            const timeLabel = now.getHours() + ':' + String(now.getMinutes()).padStart(2, '0');
                            chart.data.labels.push(timeLabel);
                            chart.data.datasets[0].data.push(data.currentCount);
                            
                            // Keep only last 20 points
                            if (chart.data.labels.length > 20) {
                                chart.data.labels.shift();
                                chart.data.datasets[0].data.shift();
                            }
                            chart.update('none');
                            
                        } catch (error) {
                            console.error('Failed to fetch data:', error);
                        }
                    }
                    
                    async function exportData() {
                        window.location.href = '/api/export?format=csv';
                    }
                    
                    async function resetCounters() {
                        if (confirm('Reset all counters?')) {
                            await fetch('/api/reset', { method: 'POST' });
                        }
                    }
                    
                    // Update every second
                    setInterval(updateData, 1000);
                    updateData();
                </script>
            </body>
            </html>
        """.trimIndent()

        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun serveAnalytics(): Response {
        val analytics = trafficAnalyzer.getAnalytics()
        val json = gson.toJson(analytics)
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    private fun handleZones(session: IHTTPSession): Response {
        return when (session.method) {
            Method.GET -> {
                val zones = configManager.getZones()
                newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(zones))
            }
            Method.POST -> {
                val files = HashMap<String, String>()
                session.parseBody(files)
                val postData = files["postData"] ?: return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    """{"error": "No data"}"""
                )

                try {
                    val zone = gson.fromJson(postData, ConfigManager.Zone::class.java)
                    configManager.saveZone(zone)
                    newFixedLengthResponse(Response.Status.OK, "application/json", """{"success": true}""")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to save zone")
                    newFixedLengthResponse(
                        Response.Status.BAD_REQUEST,
                        "application/json",
                        """{"error": "${e.message}"}"""
                    )
                }
            }
            else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method not allowed")
        }
    }

    private fun handleSettings(session: IHTTPSession): Response {
        return when (session.method) {
            Method.GET -> {
                val settings = configManager.getSettings()
                newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(settings))
            }
            Method.POST -> {
                val files = HashMap<String, String>()
                session.parseBody(files)
                val postData = files["postData"] ?: return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    """{"error": "No data"}"""
                )

                try {
                    val settings = gson.fromJson(postData, ConfigManager.Settings::class.java)
                    configManager.saveSettings(settings)
                    newFixedLengthResponse(Response.Status.OK, "application/json", """{"success": true}""")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to save settings")
                    newFixedLengthResponse(
                        Response.Status.BAD_REQUEST,
                        "application/json",
                        """{"error": "${e.message}"}"""
                    )
                }
            }
            else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method not allowed")
        }
    }

    private fun handleReset(): Response {
        trafficAnalyzer.resetCounters()
        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"success": true}""")
    }

    private fun handleExport(session: IHTTPSession): Response {
        val params = session.parameters
        val format = params["format"]?.firstOrNull() ?: "csv"

        if (format != "csv") {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                """{"error": "Unsupported format"}"""
            )
        }

        val csv = runBlocking {
            val endDate = Date()
            val startDate = Date(endDate.time - 24 * 60 * 60 * 1000) // Last 24 hours
            analyticsRepository.exportToCSV(startDate, endDate)
        }

        val response = newFixedLengthResponse(Response.Status.OK, "text/csv", csv)
        response.addHeader("Content-Disposition", "attachment; filename=\"foottraffic_export.csv\"")
        return response
    }

    private fun serveStatic(uri: String): Response {
        // Serve static files if needed
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
    }
}