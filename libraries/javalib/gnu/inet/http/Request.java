/*
 * $Id: Request.java,v 1.1 2004/07/25 22:46:19 dalibor Exp $
 * Copyright (C) 2004 The Free Software Foundation
 * 
 * This file is part of GNU inetlib, a library.
 * 
 * GNU inetlib is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * 
 * GNU inetlib is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 * 
 * As a special exception, if you link this library with other files to
 * produce an executable, this library does not by itself cause the
 * resulting executable to be covered by the GNU General Public License.
 * This exception does not however invalidate any other reasons why the
 * executable file might be covered by the GNU General Public License.
 */

package gnu.inet.http;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ProtocolException;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

import gnu.inet.http.event.RequestEvent;
import gnu.inet.util.BASE64;
import gnu.inet.util.LineInputStream;

/**
 * A single HTTP request.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Request
{

  /**
   * The connection context in which this request is invoked.
   */
  protected final HTTPConnection connection;

  /**
   * The HTTP method to invoke.
   */
  protected final String method;

  /**
   * The path identifying the resource.
   * This string must conform to the abs_path definition given in RFC2396,
   * with an optional "?query" part, and must be URI-escaped by the caller.
   */
  protected final String path;

  /**
   * The headers in this request.
   */
  protected final Headers requestHeaders;

  /**
   * The request body provider.
   */
  protected RequestBodyWriter requestBodyWriter;

  /**
   * The response body reader.
   */
  protected ResponseBodyReader responseBodyReader;

  /**
   * Map of response header handlers.
   */
  protected Map responseHeaderHandlers;

  /**
   * The authenticator.
   */
  protected Authenticator authenticator;

  /**
   * Whether this request has been dispatched yet.
   */
  private boolean dispatched;

  /**
   * Constructor for a new request.
   * @param connection the connection context
   * @param method the HTTP method
   * @param path the resource path including query part
   */
  protected Request (HTTPConnection connection, String method,
                     String path)
  {
    this.connection = connection;
    this.method = method;
    this.path = path;
    requestHeaders = new Headers ();
    responseHeaderHandlers = new HashMap ();
  }

  /**
   * Returns the connection associated with this request.
   * @see #connection
   */
  public HTTPConnection getConnection ()
  {
    return connection;
  }

  /**
   * Returns the HTTP method to invoke.
   * @see #method
   */
  public String getMethod ()
  {
    return method;
  }

  /**
   * Returns the resource path.
   * @see #path
   */
  public String getPath ()
  {
    return path;
  }

  /**
   * Returns the full request-URI represented by this request, as specified
   * by HTTP/1.1.
   */
  public String getRequestURI ()
  {
    return connection.getURI () + path;
  }

  /**
   * Returns the headers in this request.
   */
  public Headers getHeaders ()
  {
    return requestHeaders;
  }

  /**
   * Returns the value of the specified header in this request.
   * @param name the header name
   */
  public String getHeader (String name)
  {
    return requestHeaders.getValue (name);
  }

  /**
   * Returns the value of the specified header in this request as an integer.
   * @param name the header name
   */
  public int getIntHeader (String name)
  {
    return requestHeaders.getIntValue (name);
  }

  /**
   * Returns the value of the specified header in this request as a date.
   * @param name the header name
   */
  public Date getDateHeader (String name)
  {
    return requestHeaders.getDateValue (name);
  }

  /**
   * Sets the specified header in this request.
   * @param name the header name
   * @param value the header value
   */
  public void setHeader (String name, String value)
  {
    requestHeaders.put (name, value);
  }

  /**
   * Convenience method to set the entire request body.
   * @param requestBody the request body content
   */
  public void setRequestBody (byte[] requestBody)
  {
    setRequestBodyWriter (new ByteArrayRequestBodyWriter (requestBody));
  }

  /**
   * Sets the request body provider.
   * @param requestBodyWriter the handler used to obtain the request body
   */
  public void setRequestBodyWriter (RequestBodyWriter requestBodyWriter)
  {
    this.requestBodyWriter = requestBodyWriter;
  }

  /**
   * Sets the response body reader.
   * @param responseBodyReader the handler to receive notifications of
   * response body content
   */
  public void setResponseBodyReader (ResponseBodyReader responseBodyReader)
  {
    this.responseBodyReader = responseBodyReader;
  }

  /**
   * Sets a callback handler to be invoked for the specified header name.
   * @param name the header name
   * @param handler the handler to receive the value for the header
   */
  public void setResponseHeaderHandler (String name,
                                        ResponseHeaderHandler handler)
  {
    responseHeaderHandlers.put (name, handler);
  }

  /**
   * Sets an authenticator that can be used to handle authentication
   * automatically.
   * @param authenticator the authenticator
   */
  public void setAuthenticator (Authenticator authenticator)
  {
    this.authenticator = authenticator;
  }

  /**
   * Dispatches this request.
   * A request can only be dispatched once; calling this method a second
   * time results in a protocol exception.
   * @exception IOException if an I/O error occurred
   * @return an HTTP response object representing the result of the operation
   */
  public Response dispatch ()
    throws IOException
  {
    if (dispatched)
      {
        throw new ProtocolException ("request already dispatched");
      }
    final String CRLF = "\r\n";
    final String HEADER_SEP = ": ";
    final String US_ASCII = "US-ASCII";
    final String version = connection.getVersion ();
    Response response;
    int contentLength = -1;
    boolean retry = false;
    int attempts = 0;
    boolean expectingContinue = false;
    if (requestBodyWriter != null)
      {
        contentLength = requestBodyWriter.getContentLength ();
        if (contentLength > 4096) // TODO make configurable
          {
            expectingContinue = true;
            setHeader ("Expect", "100-continue");
          }
        else
          {
            setHeader ("Content-Length", Integer.toString (contentLength));
          }
      }
    
    // Loop while authentication fails or continue
    do
      {
        retry = false;
        // Send request
        connection.fireRequestEvent (RequestEvent.REQUEST_SENDING, this);
        
        // Get socket output and input streams
        Socket socket = connection.getSocket ();
        OutputStream out = socket.getOutputStream ();
        LineInputStream in = new LineInputStream (socket.getInputStream ());
        // Request line
        String requestUri =
          connection.isUsingProxy () ? getRequestURI () : path;
        String line = method + ' ' + requestUri + ' ' + version + CRLF;
        out.write (line.getBytes (US_ASCII));
        // Request headers
        for (Iterator i = requestHeaders.keySet ().iterator ();
             i.hasNext (); )
          {
            String name = (String) i.next ();
            String value = (String) requestHeaders.get (name);
            line = name + HEADER_SEP + value + CRLF;
            out.write (line.getBytes (US_ASCII));
          }
        out.write (CRLF.getBytes (US_ASCII));
        // Request body
        if (requestBodyWriter != null && !expectingContinue)
          {
            byte[] buffer = new byte[4096];
            int len;
            int count = 0;
            
            requestBodyWriter.reset ();
            do
              {
                len = requestBodyWriter.write (buffer);
                if (len > 0)
                  {
                    out.write (buffer, 0, len);
                  }
                count += len;
              }
            while (len > -1 && count < contentLength);
            out.write (CRLF.getBytes (US_ASCII));
          }
        // Sent event
        connection.fireRequestEvent (RequestEvent.REQUEST_SENT, this);
        // Get response
        response = readResponse (in);
        int sc = response.getCode ();
        if (sc == 401 && authenticator != null)
          {
            if (authenticate (response, attempts++))
              {
                retry = true;
              }
          }
        else if (sc == 100 && expectingContinue)
          {
            requestHeaders.remove ("Expect");
            setHeader ("Content-Length", Integer.toString (contentLength));
            expectingContinue = false;
            retry = true;
          }
      }
    while (retry);
    return response;
  }

  Response readResponse (LineInputStream in)
    throws IOException
  {
    String line;
    int len;
    
    // Read response status line
    line = in.readLine ();
    if (!line.startsWith ("HTTP/"))
      {
        throw new ProtocolException (line);
      }
    len = line.length ();
    int start = 5, end = 6;
    while (line.charAt (end) != '.')
      {
        end++;
      }
    int majorVersion = Integer.parseInt (line.substring (start, end));
    start = end + 1;
    end = start + 1;
    while (line.charAt (end) != ' ')
      {
        end++;
      }
    int minorVersion = Integer.parseInt (line.substring (start, end));
    start = end + 1;
    end = start + 3;
    int code = Integer.parseInt (line.substring (start, end));
    String message = line.substring (end + 1, len - 1);
    // Read response headers
    Headers responseHeaders = new Headers ();
    responseHeaders.parse (in);
    notifyHeaderHandlers (responseHeaders);
    // Construct response
    int codeClass = code / 100;
    Response ret = new Response (majorVersion, minorVersion, code,
                                 codeClass, message, responseHeaders);
    switch (code)
      {
      case 204:
      case 205:
        break;
      default:
        // Does response body reader want body?
        boolean notify = (responseBodyReader != null);
        if (notify)
          {
            if (!responseBodyReader.accept (this, ret))
              {
                notify = false;
              }
          }
        readResponseBody (ret, in, notify);
      }
    return ret;
  }

  void notifyHeaderHandlers (Headers headers)
  {
    for (Iterator i = headers.entrySet ().iterator (); i.hasNext (); )
      {
        Map.Entry entry = (Map.Entry) i.next ();
        String name = (String) entry.getKey ();
        ResponseHeaderHandler handler =
          (ResponseHeaderHandler) responseHeaderHandlers.get (name);
        if (handler != null)
          {
            String value = (String) entry.getValue ();
            handler.setValue (value);
          }
      }
  }

  void readResponseBody (Response response, InputStream in,
                         boolean notify)
    throws IOException
  {
    byte[] buffer = new byte[4096];
    int contentLength = -1;
    Headers trailer = null;
    
    String transferCoding = response.getHeader ("Transfer-Encoding");
    if ("chunked".equalsIgnoreCase (transferCoding))
      {
        trailer = new Headers ();
        in = new ChunkedInputStream (in, trailer);
      } 
    else
      {
        contentLength = response.getIntHeader ("Content-Length");
      }
    
    // Persistent connections are the default in HTTP/1.1
    boolean doClose = "close".equalsIgnoreCase (getHeader ("Connection")) ||
      "close".equalsIgnoreCase (response.getHeader ("Connection")) ||
      (connection.majorVersion == 1 && connection.minorVersion == 0) ||
      (response.majorVersion == 1 && response.minorVersion == 0);
    
    int count = contentLength;
    int len = (count > -1) ? count : buffer.length;
    len = (len > buffer.length) ? buffer.length : len;
    while (len > -1)
      {
        len = in.read (buffer, 0, len);
        if (len < 0)
          {
            // EOF
            connection.closeConnection ();
            break;
          }
        if (notify)
          {
            responseBodyReader.read (buffer, 0, len);
          }
        if (count > -1)
          {
            count -= len;
            if (count < 1)
              {
                if (doClose)
                  {
                    connection.closeConnection ();
                  }
                break;
              }
          }
      }
    if (notify)
      {
        responseBodyReader.close ();
      }
    if (trailer != null)
      {
        response.getHeaders ().putAll (trailer);
        notifyHeaderHandlers (trailer);
      }
  }

  boolean authenticate (Response response, int attempts)
    throws IOException
  {
    String challenge = response.getHeader ("WWW-Authenticate");
    if (challenge == null)
      {
        challenge = response.getHeader ("Proxy-Authenticate");
      }
    int si = challenge.indexOf (' ');
    String scheme = (si == -1) ? challenge : challenge.substring (0, si);
    if ("Basic".equalsIgnoreCase (scheme))
      {
        Properties params = parseAuthParams (challenge.substring (si + 1));
        String realm = params.getProperty ("realm");
        Credentials creds = authenticator.getCredentials (realm, attempts);
        String userPass = creds.getUsername () + ':' + creds.getPassword ();
        byte[] b_userPass = userPass.getBytes ("US-ASCII");
        byte[] b_encoded = BASE64.encode (b_userPass);
        String authorization =
          scheme + " " + new String (b_encoded, "US-ASCII");
        setHeader ("Authorization", authorization);
        return true;
      }
    else if ("Digest".equalsIgnoreCase (scheme))
      {
        Properties params = parseAuthParams (challenge.substring (si + 1));
        String realm = params.getProperty ("realm");
        String nonce = params.getProperty ("nonce");
        String qop = params.getProperty ("qop");
        String algorithm = params.getProperty ("algorithm");
        String digestUri = getRequestURI ();
        Credentials creds = authenticator.getCredentials (realm, attempts);
        String username = creds.getUsername ();
        String password = creds.getPassword ();
        try
          {
            MessageDigest md5 = MessageDigest.getInstance ("MD5");
            final byte[] COLON = { 0x3a };
            
            // Calculate H(A1)
            md5.reset ();
            md5.update (username.getBytes ("US-ASCII"));
            md5.update (COLON);
            md5.update (realm.getBytes ("US-ASCII"));
            md5.update (COLON);
            md5.update (password.getBytes ("US-ASCII"));
            byte[] ha1 = md5.digest ();
            if ("md5-sess".equals (algorithm))
              {
                byte[] cnonce = generateNonce ();
                md5.reset ();
                md5.update (ha1);
                md5.update (COLON);
                md5.update (nonce.getBytes ("US-ASCII"));
                md5.update (COLON);
                md5.update (cnonce);
                ha1 = md5.digest();
              }
            String ha1Hex = toHexString (ha1);
            
            // Calculate H(A2)
            md5.reset ();
            md5.update (method.getBytes ("US-ASCII"));
            md5.update (COLON);
            md5.update (digestUri.getBytes ("US-ASCII"));
            if ("auth-int".equals (qop))
              {
                byte[] hEntity = null; // TODO hash of entity body
                md5.update (COLON);
                md5.update (hEntity);
              }
            byte[] ha2 = md5.digest ();
            String ha2Hex = toHexString (ha2);
            
            // Calculate response
            md5.reset ();
            md5.update (ha1Hex.getBytes ("US-ASCII"));
            md5.update (COLON);
            md5.update (nonce.getBytes ("US-ASCII"));
            if ("auth".equals (qop) || "auth-int".equals (qop))
              {
                String nc = getNonceCount (nonce);
                byte[] cnonce = generateNonce ();
                md5.update (COLON);
                md5.update (nc.getBytes ("US-ASCII"));
                md5.update (COLON);
                md5.update (cnonce);
                md5.update (COLON);
                md5.update (qop.getBytes ("US-ASCII"));
              }
            md5.update (COLON);
            md5.update (ha2Hex.getBytes ("US-ASCII"));
            String digestResponse = toHexString (md5.digest ());
            
            String authorization = scheme + 
              " username=\"" + username + "\"" +
              " realm=\"" + realm + "\"" +
              " nonce=\"" + nonce + "\"" +
              " uri=\"" + digestUri + "\"" +
              " response=\"" + digestResponse + "\"";
            setHeader ("Authorization", authorization);
            return true;
          }
        catch (NoSuchAlgorithmException e)
          {
            return false;
          }
      }
    // Scheme not recognised
    return false;
  }

  Properties parseAuthParams (String text)
  {
    int len = text.length ();
    String key = null;
    StringBuffer buf = new StringBuffer ();
    Properties ret = new Properties ();
    boolean inQuote = false;
    for (int i = 0; i < len; i++)
      {
        char c = text.charAt (i);
        if (c == '"')
          {
            inQuote = !inQuote;
          }
        else if (c == '=' && key == null)
          {
            key = buf.toString ().trim ();
            buf.setLength (0);
          }
        else if (c == ' ' && !inQuote)
          {
            String value = unquote (buf.toString ().trim ());
            ret.put (key, value);
            key = null;
            buf.setLength (0);
          }
        else if (c != ',' || (i < (len - 1) && text.charAt (i + 1) != ' '))
          {   
            buf.append (c);
          }
      }
    if (key != null)
      {
        String value = unquote (buf.toString ().trim ());
        ret.put (key, value);
      }
    return ret;
  }

  String unquote (String text)
  {
    int len = text.length ();
    if (text.charAt (0) == '"' && text.charAt (len - 1) == '"')
      {
        return text.substring (1, len - 1);
      }
    return text;
  }

  /**
   * Returns the number of times the specified nonce value has been seen.
   */
  String getNonceCount (String nonce)
  {
    // TODO
    return "00000001";
  }

  /**
   * Client nonce value.
   */
  byte[] nonce;

  /**
   * Generates a new client nonce value.
   */
  byte[] generateNonce ()
    throws IOException, NoSuchAlgorithmException
  {
    if (nonce == null)
      {
        long time = System.currentTimeMillis ();
        MessageDigest md5 = MessageDigest.getInstance ("MD5");
        md5.update (Long.toString (time).getBytes ("US-ASCII"));
        nonce = md5.digest ();
      }
    return nonce;
  }

  String toHexString (byte[] bytes)
  {
    char[] ret = new char[bytes.length * 2];
    for (int i = 0, j = 0; i < bytes.length; i++)
      {
        int c = (int) bytes[i];
        if (c < 0)
          {
            c += 0x100;
          }
        ret[j++] = Character.forDigit (c / 0x10, 0x10);
        ret[j++] = Character.forDigit (c % 0x10, 0x10);
      }
    return new String (ret);
  }

}