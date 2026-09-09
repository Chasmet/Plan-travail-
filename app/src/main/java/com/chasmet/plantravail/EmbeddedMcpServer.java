package com.chasmet.plantravail;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class EmbeddedMcpServer extends NanoHTTPD {
    public static final int PORT = 8765;
    private final WorkDatabase database;

    public EmbeddedMcpServer(Context context) {
        super("127.0.0.1", PORT);
        database = new WorkDatabase(context.getApplicationContext());
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            if (Method.GET.equals(session.getMethod()) && "/health".equals(session.getUri())) {
                JSONObject health = new JSONObject();
                health.put("ok", true);
                health.put("name", "Plan Travail Orsay MCP");
                health.put("port", PORT);
                return json(Response.Status.OK, health);
            }
            if (!"/mcp".equals(session.getUri())) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "Not found");
            }
            if (!Method.POST.equals(session.getMethod())) {
                return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain; charset=utf-8", "POST required");
            }

            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            String body = files.get("postData");
            if (body == null || body.trim().isEmpty()) {
                return error(null, -32700, "JSON vide");
            }
            JSONObject request = new JSONObject(body);
            Object id = request.opt("id");
            String method = request.optString("method", "");

            if ("notifications/initialized".equals(method)) {
                return newFixedLengthResponse(Response.Status.NO_CONTENT, "application/json", "");
            }
            if ("initialize".equals(method)) {
                JSONObject result = new JSONObject();
                result.put("protocolVersion", "2025-06-18");
                result.put("capabilities", new JSONObject().put("tools", new JSONObject().put("listChanged", false)));
                result.put("serverInfo", new JSONObject().put("name", "plan-travail-orsay").put("version", BuildConfig.VERSION_NAME));
                return result(id, result);
            }
            if ("ping".equals(method)) return result(id, new JSONObject());
            if ("tools/list".equals(method)) return result(id, new JSONObject().put("tools", tools()));
            if ("tools/call".equals(method)) return callTool(id, request.optJSONObject("params"));

            return error(id, -32601, "Méthode inconnue : " + method);
        } catch (Exception e) {
            return error(null, -32603, e.getMessage() == null ? "Erreur serveur" : e.getMessage());
        }
    }

    private JSONArray tools() throws Exception {
        JSONArray list = new JSONArray();
        list.put(tool("plan_mark_streets_done", "Marque une ou plusieurs rues d'Orsay comme effectuées à une date donnée. La couleur est calculée automatiquement selon le jour.",
                new JSONObject().put("type", "object")
                        .put("properties", new JSONObject()
                                .put("streets", new JSONObject().put("type", "array").put("items", new JSONObject().put("type", "string")))
                                .put("date", new JSONObject().put("type", "string").put("description", "Date YYYY-MM-DD, aujourd'hui si omise")))
                        .put("required", new JSONArray().put("streets"))));
        list.put(tool("plan_delete_street_trace", "Supprime le tracé d'une rue de la semaine en cours.",
                new JSONObject().put("type", "object")
                        .put("properties", new JSONObject().put("street", new JSONObject().put("type", "string")))
                        .put("required", new JSONArray().put("street"))));
        list.put(tool("plan_get_week_status", "Retourne le nombre de rues effectuées cette semaine et aujourd'hui.",
                new JSONObject().put("type", "object").put("properties", new JSONObject())));
        list.put(tool("plan_add_lexicon_note", "Ajoute une spécificité au lexique de travail.",
                new JSONObject().put("type", "object")
                        .put("properties", new JSONObject()
                                .put("title", new JSONObject().put("type", "string"))
                                .put("details", new JSONObject().put("type", "string")))
                        .put("required", new JSONArray().put("title").put("details"))));
        return list;
    }

    private JSONObject tool(String name, String description, JSONObject schema) throws Exception {
        return new JSONObject().put("name", name).put("description", description).put("inputSchema", schema);
    }

    private Response callTool(Object id, JSONObject params) throws Exception {
        if (params == null) return error(id, -32602, "Paramètres manquants");
        String name = params.optString("name", "");
        JSONObject args = params.optJSONObject("arguments");
        if (args == null) args = new JSONObject();
        String text;

        switch (name) {
            case "plan_mark_streets_done": {
                JSONArray streets = args.optJSONArray("streets");
                if (streets == null || streets.length() == 0) return toolError(id, "Aucune rue fournie");
                String date = args.optString("date", DayColor.today());
                int color = DayColor.forDate(date);
                int added = 0;
                for (int i = 0; i < streets.length(); i++) {
                    String street = streets.optString(i, "").trim();
                    if (!street.isEmpty()) {
                        database.addOrUpdate(street, date, color, "MCP intégré");
                        added++;
                    }
                }
                text = added + " rue(s) enregistrée(s) pour le " + date + ".";
                break;
            }
            case "plan_delete_street_trace": {
                String street = args.optString("street", "").trim();
                if (street.isEmpty()) return toolError(id, "Rue manquante");
                String actual = database.findCurrentWeekStreet(street);
                int deleted = actual == null ? 0 : database.deleteCurrentWeekStreet(actual);
                text = deleted > 0 ? "Tracé supprimé : " + actual : "Aucun tracé trouvé cette semaine pour " + street;
                break;
            }
            case "plan_get_week_status":
                text = "Semaine du " + database.getCurrentWeekStart() + " : " + database.getCurrentWeekCount() + " rue(s). Aujourd'hui : " + database.getTodayCount() + " rue(s).";
                break;
            case "plan_add_lexicon_note": {
                String title = args.optString("title", "").trim();
                String details = args.optString("details", "").trim();
                if (title.isEmpty() || details.isEmpty()) return toolError(id, "Titre et détails obligatoires");
                database.addLexicon(title, details);
                text = "Spécificité ajoutée au lexique : " + title;
                break;
            }
            default:
                return toolError(id, "Outil inconnu : " + name);
        }
        JSONObject result = new JSONObject().put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", text))).put("isError", false);
        return result(id, result);
    }

    private Response toolError(Object id, String message) throws Exception {
        JSONObject result = new JSONObject().put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", message))).put("isError", true);
        return result(id, result);
    }

    private Response result(Object id, JSONObject value) throws Exception {
        JSONObject root = new JSONObject().put("jsonrpc", "2.0").put("result", value);
        if (id != null && id != JSONObject.NULL) root.put("id", id);
        return json(Response.Status.OK, root);
    }

    private Response error(Object id, int code, String message) {
        try {
            JSONObject root = new JSONObject().put("jsonrpc", "2.0").put("error", new JSONObject().put("code", code).put("message", message));
            if (id != null && id != JSONObject.NULL) root.put("id", id);
            return json(Response.Status.OK, root);
        } catch (Exception ignored) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Erreur\"}}");
        }
    }

    private Response json(Response.Status status, JSONObject object) {
        Response response = newFixedLengthResponse(status, "application/json; charset=utf-8", object.toString());
        response.addHeader("Cache-Control", "no-store");
        return response;
    }
}
