package com.chasmet.plantravail;

import android.content.Context;
import fi.iki.elonen.NanoHTTPD;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public class EmbeddedMcpServer extends NanoHTTPD {
  public static final int PORT = 8765;
  private final WorkDatabase database;
  private final java.util.List<Street> catalog;

  public EmbeddedMcpServer(Context context) {
    super("127.0.0.1", PORT);
    database = WorkDatabase.getInstance(context);
    try {
      catalog = MapDataCache.local(context, "orsay_streets.json", StreetRepository::parse);
    } catch (Exception e) {
      throw new IllegalStateException("Catalogue MCP indisponible", e);
    }
  }

  @Override
  public Response serve(IHTTPSession session) {
    try {
      if (Method.GET.equals(session.getMethod()) && "/health".equals(session.getUri())) {
        JSONObject health = new JSONObject();
        health.put("ok", true);
        health.put("name", "Plan Travail Orsay MCP");
        health.put("port", PORT);
        health.put("self_hosted", true);
        return json(Response.Status.OK, health);
      }
      if (!"/mcp".equals(session.getUri()))
        return newFixedLengthResponse(
            Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "Not found");
      if (!Method.POST.equals(session.getMethod()))
        return newFixedLengthResponse(
            Response.Status.METHOD_NOT_ALLOWED, "text/plain; charset=utf-8", "POST required");

      Map<String, String> files = new HashMap<>();
      session.parseBody(files);
      String body = files.get("postData");
      if (body == null || body.trim().isEmpty()) return error(null, -32700, "JSON vide");
      JSONObject request = new JSONObject(body);
      Object id = request.opt("id");
      String method = request.optString("method", "");
      if ("notifications/initialized".equals(method))
        return newFixedLengthResponse(Response.Status.NO_CONTENT, "application/json", "");
      if ("initialize".equals(method)) {
        JSONObject result = new JSONObject();
        result.put("protocolVersion", "2025-06-18");
        result.put(
            "capabilities",
            new JSONObject().put("tools", new JSONObject().put("listChanged", false)));
        result.put(
            "serverInfo",
            new JSONObject()
                .put("name", "plan-travail-orsay")
                .put("version", BuildConfig.VERSION_NAME));
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
    list.put(
        tool(
            "plan_mark_streets_done",
            "Marque une ou plusieurs rues d'Orsay comme effectuées à une date donnée. La couleur"
                + " est calculée automatiquement selon le jour.",
            new JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    new JSONObject()
                        .put(
                            "streets",
                            new JSONObject()
                                .put("type", "array")
                                .put("items", new JSONObject().put("type", "string")))
                        .put(
                            "date",
                            new JSONObject()
                                .put("type", "string")
                                .put("description", "Date YYYY-MM-DD, aujourd'hui si omise")))
                .put("required", new JSONArray().put("streets"))));
    list.put(
        tool(
            "plan_delete_street_trace",
            "Supprime le tracé d'une rue de la semaine en cours.",
            new JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    new JSONObject().put("street", new JSONObject().put("type", "string")))
                .put("required", new JSONArray().put("street"))));
    list.put(
        tool(
            "plan_get_week_status",
            "Retourne le nombre de rues effectuées cette semaine et aujourd'hui.",
            new JSONObject().put("type", "object").put("properties", new JSONObject())));
    list.put(
        tool(
            "plan_add_lexicon_note",
            "Ajoute une spécificité au lexique de travail.",
            new JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    new JSONObject()
                        .put("title", new JSONObject().put("type", "string"))
                        .put("details", new JSONObject().put("type", "string")))
                .put("required", new JSONArray().put("title").put("details"))));
    return list;
  }

  private JSONObject tool(String name, String description, JSONObject schema) throws Exception {
    return new JSONObject()
        .put("name", name)
        .put("description", description)
        .put("inputSchema", schema);
  }

  private Response callTool(Object id, JSONObject params) throws Exception {
    if (params == null) return error(id, -32602, "Paramètres manquants");
    String name = params.optString("name", "");
    JSONObject args = params.optJSONObject("arguments");
    if (args == null) args = new JSONObject();
    if ("plan_get_week_status".equals(name))
      return result(
          id,
          new JSONObject()
              .put(
                  "content",
                  new JSONArray()
                      .put(
                          new JSONObject()
                              .put("type", "text")
                              .put(
                                  "text",
                                  database.getCurrentWeekCount()
                                      + " rues commencées, "
                                      + database.getCompletedCount(DayColor.today())
                                      + " terminées, semaine du "
                                      + database.getCurrentWeekStart())))
              .put("isError", false));
    String action;
    switch (name) {
      case "plan_mark_streets_done":
        action = "mark_streets";
        break;
      case "plan_delete_street_trace":
        action = "delete_street";
        break;
      case "plan_add_lexicon_note":
        action = "add_lexicon";
        break;
      default:
        return toolError(id, "Outil inconnu : " + name);
    }
    JSONObject command =
        new JSONObject(args.toString())
            .put("id", "local-" + java.util.UUID.randomUUID())
            .put("action", action);
    database.receiveCommand(command);
    JSONObject applied = new CommandProcessor(database).apply(command, catalog);
    database.acknowledge(command.getString("id"));
    return result(
        id,
        new JSONObject()
            .put(
                "content",
                new JSONArray()
                    .put(new JSONObject().put("type", "text").put("text", applied.toString())))
            .put("isError", !applied.optBoolean("success")));
  }

  private Response toolError(Object id, String message) throws Exception {
    JSONObject result =
        new JSONObject()
            .put(
                "content",
                new JSONArray().put(new JSONObject().put("type", "text").put("text", message)))
            .put("isError", true);
    return result(id, result);
  }

  private Response result(Object id, JSONObject value) throws Exception {
    JSONObject root = new JSONObject().put("jsonrpc", "2.0").put("result", value);
    if (id != null && id != JSONObject.NULL) root.put("id", id);
    return json(Response.Status.OK, root);
  }

  private Response error(Object id, int code, String message) {
    try {
      JSONObject root =
          new JSONObject()
              .put("jsonrpc", "2.0")
              .put("error", new JSONObject().put("code", code).put("message", message));
      if (id != null && id != JSONObject.NULL) root.put("id", id);
      return json(Response.Status.OK, root);
    } catch (Exception ignored) {
      return newFixedLengthResponse(
          Response.Status.INTERNAL_ERROR,
          "application/json",
          "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Erreur\"}}");
    }
  }

  private Response json(Response.Status status, JSONObject object) {
    Response response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", object.toString());
    response.addHeader("Cache-Control", "no-store");
    return response;
  }
}
