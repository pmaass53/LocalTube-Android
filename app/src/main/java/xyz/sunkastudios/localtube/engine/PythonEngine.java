package xyz.sunkastudios.localtube.engine;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import xyz.sunkastudios.localtube.VideoItem;

public class PythonEngine {
    private static final Gson gson = new Gson();

    public static PyObject getModule(String moduleName) {
        Python py = Python.getInstance();
        return py.getModule(moduleName);
    }

    public static List<VideoItem> searchAnime(String query) {
        try {
            PyObject animeModule = getModule("anime_provider");
            PyObject results = animeModule.callAttr("search_anime", query);
            
            List<VideoItem> animeItems = new ArrayList<>();
            List<PyObject> pyList = results.asList();
            for (PyObject obj : pyList) {
                Map<PyObject, PyObject> map = obj.asMap();
                VideoItem item = new VideoItem();
                
                PyObject titleObj = map.get(PyObject.fromJava("title"));
                PyObject urlObj = map.get(PyObject.fromJava("url"));
                PyObject idObj = map.get(PyObject.fromJava("id"));
                
                if (titleObj != null) item.setTitle(titleObj.toString());
                if (urlObj != null) item.setUrl(urlObj.toString());
                if (idObj != null) item.setId(idObj.toString());
                
                PyObject descObjInitial = map.get(PyObject.fromJava("description"));
                if (descObjInitial != null) item.setDescription(descObjInitial.toString());
                
                PyObject coverObjInitial = map.get(PyObject.fromJava("cover_large"));
                if (coverObjInitial != null) item.setCoverLarge(coverObjInitial.toString());

                animeItems.add(item);
            }
            return animeItems;
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public static Map<String, Map<String, String>> getAnimeImagesBatch(List<VideoItem> items) {
        try {
            PyObject animeModule = getModule("anime_provider");
            
            List<Map<String, String>> javaList = new ArrayList<>();
            for (VideoItem item : items) {
                java.util.HashMap<String, String> m = new java.util.HashMap<>();
                m.put("id", item.getId());
                m.put("title", item.getTitle());
                javaList.add(m);
            }
            
            String jsonInput = gson.toJson(javaList);
            PyObject results = animeModule.callAttr("get_anime_images_batch", jsonInput);
            Map<PyObject, PyObject> pyMap = results.asMap();
            
            java.util.HashMap<String, Map<String, String>> finalResults = new java.util.HashMap<>();
            for (Map.Entry<PyObject, PyObject> entry : pyMap.entrySet()) {
                String id = entry.getKey().toString();
                Map<PyObject, PyObject> imgPyMap = entry.getValue().asMap();
                
                java.util.HashMap<String, String> imgMap = new java.util.HashMap<>();
                PyObject bannerObj = imgPyMap.get(PyObject.fromJava("banner"));
                PyObject coverObj = imgPyMap.get(PyObject.fromJava("cover_large"));
                PyObject descObj = imgPyMap.get(PyObject.fromJava("description"));
                
                imgMap.put("banner", bannerObj != null ? bannerObj.toString() : "");
                imgMap.put("cover_large", coverObj != null ? coverObj.toString() : "");
                imgMap.put("description", descObj != null ? descObj.toString() : "");
                
                finalResults.put(id, imgMap);
            }
            return finalResults;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static Map<String, String> getAnimeDetails(String animeId, String title) {
        try {
            PyObject animeModule = getModule("anime_provider");
            PyObject results = animeModule.callAttr("get_anime_details", animeId, title);
            Map<PyObject, PyObject> pyMap = results.asMap();
            
            java.util.HashMap<String, String> details = new java.util.HashMap<>();
            PyObject descObj = pyMap.get(PyObject.fromJava("description"));
            PyObject bannerObj = pyMap.get(PyObject.fromJava("banner"));
            PyObject coverObj = pyMap.get(PyObject.fromJava("cover_large"));
            
            details.put("description", descObj != null ? descObj.toString() : "");
            details.put("banner", bannerObj != null ? bannerObj.toString() : "");
            details.put("cover_large", coverObj != null ? coverObj.toString() : "");
            return details;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static List<Integer> getEpisodesList(String animeId, String lang) {
        try {
            PyObject animeModule = getModule("anime_provider");
            PyObject results = animeModule.callAttr("get_episodes_list", animeId, lang);
            
            List<Integer> episodes = new ArrayList<>();
            for (PyObject obj : results.asList()) {
                episodes.add(obj.toInt());
            }
            return episodes;
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public static String getAvailableStreams(String animeId, String episodeId, String lang) {
        try {
            PyObject animeModule = getModule("anime_provider");
            PyObject result = animeModule.callAttr("get_available_streams", animeId, episodeId, lang);
            return result != null ? result.toString() : "[]";
        } catch (Exception e) {
            e.printStackTrace();
            return "[]";
        }
    }

    public static String getStreamUrl(String animeId, String episodeId, String lang) {
        try {
            PyObject animeModule = getModule("anime_provider");
            PyObject streamUrl = animeModule.callAttr("get_stream_url", animeId, episodeId, lang);
            return streamUrl != null ? streamUrl.toString() : null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
