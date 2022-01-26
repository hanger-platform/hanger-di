/*
 * Copyright (c) 2022 Dafiti Group
 * 
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 * 
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 * 
 */
package br.com.dafiti.google.pagespeed.insights;

import br.com.dafiti.mitt.Mitt;
import br.com.dafiti.mitt.cli.CommandLineInterface;
import br.com.dafiti.mitt.model.Configuration;
import br.com.dafiti.mitt.transformation.embedded.Concat;
import br.com.dafiti.mitt.transformation.embedded.Now;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

/**
 *
 * @author Helio Leal
 */
public class GooglePagespeedInsights {

    private static final Logger LOG = Logger.getLogger(GooglePagespeedInsights.class.getName());
    private static final String GOOGLE_PSI_ENDPOINT = "https://www.googleapis.com/pagespeedonline/v5/runPagespeed";
    private static final int MAX_RETRY = 3;

    /**
     * Google Pagespeed Insights API File transfer
     *
     * @param args cli parameteres provided by command line.
     */
    public static void main(String[] args) {
        LOG.info("GLOVE - Google Pagespeed Insights Extractor started");

        //Defines URL parameters variable
        JSONObject parameters = null;

        //Define the mitt.
        Mitt mitt = new Mitt();

        try {
            //Defines parameters.
            mitt.getConfiguration()
                    .addParameter("c", "credentials", "Credentials file", "", true, false)
                    .addParameter("o", "output", "Output file", "", true, false)
                    .addParameter("f", "field", "Fields to be retrieved from an endpoint in a JsonPath fashion", "", true, false)
                    .addParameter("e", "url", "The URL to fetch and analyze", "", true, false)
                    .addParameter("p", "parameters", "(Optional) Endpoint parameters", "", true, true)
                    .addParameter("b", "object", "(Optional) Json object", "", true, true)
                    .addParameter("a", "partition", "(Optional)  Partition, divided by + if has more than one field", "")
                    .addParameter("k", "key", "(Optional) Unique key, divided by + if has more than one field", "");

            //Reads the command line interface. 
            CommandLineInterface cli = mitt.getCommandLineInterface(args);

            //Defines output file.
            mitt.setOutputFile(cli.getParameter("output"));

            //Defines fields.
            Configuration configuration = mitt.getConfiguration();

            if (cli.hasParameter("partition")) {
                configuration
                        .addCustomField("partition_field", new Concat((List) cli.getParameterAsList("partition", "\\+")));
            }

            if (cli.hasParameter("key")) {
                configuration
                        .addCustomField("custom_primary_key", new Concat((List) cli.getParameterAsList("key", "\\+")));
            }

            configuration
                    .addCustomField("etl_load_date", new Now())
                    .addField(cli.getParameterAsList("field", "\\+"));

            //Reads the credentials file. 
            JSONParser parser = new JSONParser();
            JSONObject credentials = (JSONObject) parser.parse(new FileReader(cli.getParameter("credentials")));

            //Retrives API credentials. 
            String key = credentials.get("key").toString();

            //Identifies endpoint parameters. 
            String endpointParameter = cli.getParameter("parameters");

            if (endpointParameter != null && !endpointParameter.isEmpty()) {
                try {
                    parameters = (JSONObject) parser.parse(endpointParameter);
                } catch (ParseException ex) {
                    LOG.log(Level.INFO, "Fail parsing endpoint parameters: {0}", endpointParameter);
                }
            }

            //Connect to the API. 
            try (CloseableHttpClient client = HttpClients.createDefault()) {
                HttpGet httpGet = new HttpGet(GOOGLE_PSI_ENDPOINT);

                //Sets default URI parameters. 
                URIBuilder uriBuilder = new URIBuilder(httpGet.getURI())
                        .addParameter("url", cli.getParameter("url"))
                        .addParameter("key", key);

                //Sets endpoint URI parameters. 
                if (parameters != null && !parameters.isEmpty()) {
                    for (Object k : parameters.keySet()) {
                        uriBuilder.addParameter((String) k, (String) parameters.get(k));
                    }
                }

                //Sets URI parameters. 
                httpGet.setURI(uriBuilder.build());

                //Executes a request. 
                CloseableHttpResponse response = client.execute(httpGet);

                //Gets a reponse entity. 
                String entity = EntityUtils.toString(response.getEntity(), "UTF-8");

                if (!entity.isEmpty()) {
                    JSONObject json = (JSONObject) new JSONParser().parse(entity);

                    //Identifies if there are payload to process. 
                    if (!json.isEmpty()) {

                        //Identifies the response status code.                                
                        switch ((int) response.getStatusLine().getStatusCode()) {

                            case 200 /*OK*/:
                                Object object;

                                //Identifies which object should be picked up from the payload.
                                if (cli.getParameter("object") == null || cli.getParameter("object").isEmpty()) {
                                    object = json;
                                } else {
                                    object = json.get(cli.getParameter("object"));
                                }

                                //Identifies if the payload is an array or an object.
                                if (object instanceof JSONArray) {
                                    if (!((JSONArray) object).isEmpty()) {
                                        ((JSONArray) object).forEach(item -> {
                                            List record = new ArrayList();

                                            mitt.getConfiguration()
                                                    .getOriginalFieldName()
                                                    .forEach(field -> {
                                                        try {
                                                            record.add(JsonPath.read(item, "$." + field));
                                                        } catch (PathNotFoundException ex) {
                                                            record.add("");
                                                        }
                                                    });

                                            mitt.write(record);
                                        });
                                    }
                                } else if (object instanceof JSONObject) {
                                    if (!((JSONObject) object).isEmpty()) {
                                        List record = new ArrayList();

                                        mitt.getConfiguration()
                                                .getOriginalFieldName()
                                                .forEach(field -> {
                                                    try {
                                                        record.add(JsonPath.read(object, "$." + field));
                                                    } catch (PathNotFoundException ex) {
                                                        record.add("");
                                                    }
                                                });

                                        mitt.write(record);
                                    }
                                }

                                break;
                            default:
                                throw new Exception("HTTP Exception " + response.getStatusLine().getStatusCode());
                        }
                    }
                } else {
                    throw new Exception("Empty response entity for request " + httpGet.getURI());
                }
            }
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "GLOVE - Google Pagespeed Insights API extractor fail: ", ex);
            System.exit(1);
        } finally {
            mitt.close();
        }

        LOG.info("GLOVE - Google Pagespeed Insights extractor finalized");
    }
}
