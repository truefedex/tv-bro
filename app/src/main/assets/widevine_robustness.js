// Widevine in WebView (WebViewWidevine.kt runs it at the start of every document). WebView gives Widevine only at
// SW_SECURE_CRYPTO (L3: no secure decoding, no hardware levels), and refuses a request that asks for more. Sites
// ask for SW_SECURE_DECODE where desktop Chrome gives it, so in the desktop view their players stop before they
// ask for a licence (Sweet.tv: "unknown error"), while the license servers take L3 (as from desktop Chrome, whose
// Widevine is L3 too). A Widevine request that WebView refuses is asked again with SW_SECURE_DECODE lowered to
// SW_SECURE_CRYPTO; the hardware levels are left as they are (WebView gives none of them).
(function() {
    var navigatorProto = window.Navigator && Navigator.prototype;
    var original = navigatorProto && navigatorProto.requestMediaKeySystemAccess;
    if (!original || original.tvBroRobustness) return;
    var LOWER = {SW_SECURE_DECODE: 'SW_SECURE_CRYPTO'};

    function copy(object) {
        var result = {};
        for (var key in object) result[key] = object[key];
        return result;
    }

    // the configurations with the lowered robustness, null if none of them asks for it
    function lowered(configs) {
        var changed = false;
        var result = Array.prototype.map.call(configs, function(config) {
            var config2 = copy(config);
            ['videoCapabilities', 'audioCapabilities'].forEach(function(key) {
                if (!config[key]) return;
                config2[key] = Array.prototype.map.call(config[key], function(capability) {
                    var lower = capability && LOWER[capability.robustness];
                    if (!lower) return capability;
                    changed = true;
                    var capability2 = copy(capability);
                    capability2.robustness = lower;
                    return capability2;
                });
            });
            return config2;
        });
        return changed ? result : null;
    }

    function requestMediaKeySystemAccess(keySystem, configs) {
        var self = this;
        var request = original.call(self, keySystem, configs);
        if (!/^com\.widevine\.alpha/.test(String(keySystem)) || !configs || typeof configs.length != 'number') {
            return request;
        }
        return request.catch(function(e) {
            var retry = e && e.name == 'NotSupportedError' ? lowered(configs) : null;
            if (!retry) throw e;
            return original.call(self, keySystem, retry);
        });
    }
    requestMediaKeySystemAccess.tvBroRobustness = true;
    navigatorProto.requestMediaKeySystemAccess = requestMediaKeySystemAccess;
})();
