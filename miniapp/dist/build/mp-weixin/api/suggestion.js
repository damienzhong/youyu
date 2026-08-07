"use strict";const t=require("../utils/request.js");exports.fetchSuggestions=function(e){return t.http.get("/transactions/suggestions",function(t){return null!=t?{ledgerId:t}:void 0}(e))};
