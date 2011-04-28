package org.forgerock.openidm.repo.orientdb.impl.query;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//import org.forgerock.openidm.objset.ObjectSetException;
import org.forgerock.openidm.objset.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TokenHandler {

    final static Logger logger = LoggerFactory.getLogger(TokenHandler.class);
    
    // The OpenIDM query token is of format ${token-name}
    Pattern tokenPattern = Pattern.compile("\\$\\{(.+?)\\}");

    /**
     * Replaces a query string with tokens of format ${token-name} with the values from the
     * passed in map, where the token-name must be the key in the map
     * 
     * @param queryString the query with tokens
     * @param params the parameters to replace the tokens
     * @return the query with all tokens replace with their found values
     * @throws BadRequestException if token in the query is not in the passed parameters
     */
    String replaceTokensWithValues(String queryString, Map<String, String> params) 
            throws BadRequestException {
        java.util.regex.Matcher matcher = tokenPattern.matcher(queryString);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String tokenKey = matcher.group(1);       
            if (!params.containsKey(tokenKey)) {
                // fail with an exception if token not found
                throw new BadRequestException("Missing entry in params passed to query for token " + tokenKey);
            } else {
                String replacement = params.get(tokenKey);
                if (replacement == null) {
                    replacement = "";
                }
                matcher.appendReplacement(buffer, "");
                buffer.append(replacement);
            }
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * Replaces a query string with tokens of format ${token-name} with the 
     * token format in OrientDB, which is of the form :token-name.
     * 
     * OrientDB tokens has some limitations, e.g. they can currently only be used
     * in the where clause, and hence the returned string is not guaranteed to be
     * valid for use in a prepared statement. If the parsing fails the system may
     * have to fall back onto non-prepared statements and manual token replacement.
     * 
     * @param queryString the query with OpenIDM format tokens ${token}
     * @return the query with all tokens replaced with the OrientDB style tokens :token
     */
    String replaceTokensWithOrientToken(String queryString) {
        Matcher matcher = tokenPattern.matcher(queryString);
        StringBuffer buf = new StringBuffer();
        while (matcher.find()) {
            String origToken = matcher.group(1);
            if (origToken != null && origToken.length() > 3) {
                // OrientDB token is of format :token-name
                String newToken = ":" + origToken;
                matcher.appendReplacement(buf, "");
                buf.append(newToken);
            }
        }
        matcher.appendTail(buf);
        return buf.toString();
    }
}