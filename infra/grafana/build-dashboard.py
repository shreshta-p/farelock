# Generates dashboards/farelock.json. Edit panels here rather than in the JSON.
import json
panels=[]; pid=[0]; y=[0]
DS={"type":"prometheus","uid":"prometheus"}
def row(title):
    pid[0]+=1; panels.append({"id":pid[0],"type":"row","title":title,"collapsed":False,"gridPos":{"h":1,"w":24,"x":0,"y":y[0]},"panels":[]}); y[0]+=1
def panel(title, targets, x, w, h=8, kind="timeseries", unit="short", desc="", stacked=False, extra=None, zero=False):
    pid[0]+=1
    p={"id":pid[0],"type":kind,"title":title,"description":desc,"datasource":DS,
       "gridPos":{"h":h,"w":w,"x":x,"y":y[0]},
       "targets":[{"datasource":DS,"refId":chr(65+i),"expr":e,"legendFormat":l} for i,(e,l) in enumerate(targets)],
       "fieldConfig":{"defaults":{"unit":unit,"custom":{"lineWidth":2,"fillOpacity":10,"showPoints":"never","stacking":{"mode":"normal" if stacked else "none"}}},"overrides":[]},
       "options":{"legend":{"displayMode":"list","placement":"bottom","showLegend":True},"tooltip":{"mode":"multi","sort":"desc"}}}
    if kind=="stat":
        p["fieldConfig"]["defaults"].pop("custom")
        p["options"]={"reduceOptions":{"calcs":["lastNotNull"],"fields":"","values":False},"colorMode":"value","graphMode":"area","textMode":"auto"}
    if extra: p["fieldConfig"]["defaults"].update(extra)
    if zero: p["fieldConfig"]["defaults"]["noValue"]="0"
    panels.append(p); return p
def nl(h=8): y[0]+=h

HTTP='http_server_requests_seconds'
row("Traffic")
panel("Requests per second",[(f'sum by (application) (rate({HTTP}_count{{uri!~"/actuator.*"}}[1m]))',"{{application}}")],0,8,unit="reqps",desc="All API requests, excluding metric scrapes.")
panel("Server error rate (5xx)",[(f'sum by (application) (rate({HTTP}_count{{outcome="SERVER_ERROR"}}[1m])) / sum by (application) (rate({HTTP}_count{{uri!~"/actuator.*"}}[1m]))',"{{application}}")],8,8,unit="percentunit",desc="Share of requests answered with a 5xx status.",zero=True)
panel("Client errors per second (4xx)",[(f'sum by (application, status) (rate({HTTP}_count{{outcome="CLIENT_ERROR"}}[1m]))',"{{application}} {{status}}")],16,8,unit="reqps",desc="Expected answers like 409 SOLD_OUT and 401 show up here.",zero=True)
nl()
for i,q in enumerate(["0.5","0.95","0.99"]):
    panel(f"Latency p{q[2:].ljust(2,'0') if q!='0.5' else '50'}",[(f'histogram_quantile({q}, sum by (le, application) (rate({HTTP}_bucket{{uri!~"/actuator.*"}}[1m])))',"{{application}}")],i*8,8,unit="s")
nl()
panel("p95 latency by endpoint",[(f'histogram_quantile(0.95, sum by (le, application, method, uri) (rate({HTTP}_bucket{{uri!~"/actuator.*"}}[1m])))',"{{method}} {{uri}}")],0,12,unit="s",desc="Find the slow endpoint.")
panel("Reservation → Pricing calls (p95)",[('histogram_quantile(0.95, sum by (le, uri) (rate(http_client_requests_seconds_bucket{application="reservation-service"}[1m])))',"{{uri}}")],12,12,unit="s",desc="Outbound HTTP latency to the pricing service.")
nl()
row("Bookings")
panel("Reservation attempts by outcome",[('sum by (outcome) (rate(farelock_reservations_total[1m]) * 60)',"{{outcome}}")],0,8,unit="short",desc="Per minute. sold_out = inventory exhausted.",zero=True)
panel("Inventory exhausted (sold out, last 5m)",[('sum(increase(farelock_reservations_total{outcome="sold_out"}[5m])) or vector(0)',"sold out")],8,4,kind="stat")
panel("Holds expired (last 5m)",[('sum(increase(farelock_holds_expired_total[5m])) or vector(0)',"expired")],12,4,kind="stat")
panel("Idempotency replays and conflicts",[('sum by (result) (rate(farelock_idempotency_total[1m]) * 60)',"{{result}}")],16,8,desc="Per minute. replay = retry answered from storage; conflict = key reused with a different body.",zero=True)
nl()
panel("Payments by outcome",[('sum by (outcome) (rate(farelock_payments_total[1m]) * 60)',"{{outcome}}")],0,12,desc="Per minute. unknown = timeout or lost response; reconciliation finishes these.",zero=True)
panel("Reconciliation results",[('sum by (result) (rate(farelock_reconciliation_total[1m]) * 60)',"{{result}}")],12,12,desc="Per minute. confirmed / released / still_pending.",zero=True)
nl()
row("Pricing cache")
panel("Cache hit ratio",[('sum(rate(farelock_cache_total{result="hit"}[1m])) / sum(rate(farelock_cache_total{result=~"hit|miss"}[1m]))',"hit ratio")],0,6,kind="stat",unit="percentunit")
panel("Cache lookups by result",[('sum by (result) (rate(farelock_cache_total[1m]))',"{{result}}")],6,18,unit="ops",desc="error = Redis unavailable; requests fell back to Postgres.")
nl()
row("Resources")
panel("JVM heap used",[('sum by (application) (jvm_memory_used_bytes{area="heap"})',"{{application}}")],0,8,unit="bytes")
panel("Process CPU",[('process_cpu_usage',"{{application}}")],8,8,unit="percentunit")
panel("DB connection pool",[('hikaricp_connections_active',"{{application}} active"),('hikaricp_connections_pending',"{{application}} waiting"),('hikaricp_connections_max',"{{application}} max")],16,8,desc="Waiting > 0 means requests are queueing for a database connection.")
nl()
panel("DB connection wait time (avg)",[('rate(hikaricp_connections_acquire_seconds_sum[1m]) / rate(hikaricp_connections_acquire_seconds_count[1m])',"{{application}}")],0,12,unit="s")
panel("JVM live threads",[('jvm_threads_live_threads',"{{application}}")],12,12)
dash={"uid":"farelock","title":"FareLock","tags":["farelock"],"timezone":"browser","schemaVersion":41,"version":1,
 "refresh":"30s","time":{"from":"now-15m","to":"now"},"panels":panels,"editable":True}
# Run from the repo root: python infra/grafana/build-dashboard.py
json.dump(dash, open('infra/grafana/dashboards/farelock.json', 'w'), indent=2)
print(f'wrote {len(panels)} panels')
