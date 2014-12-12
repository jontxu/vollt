package tap.parameters;

/*
 * This file is part of TAPLibrary.
 * 
 * TAPLibrary is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * TAPLibrary is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with TAPLibrary.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * Copyright 2014 - Astronomisches Rechen Institut (ARI)
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tap.TAPException;
import tap.TAPJob;
import uws.UWSException;
import uws.service.file.UWSFileManager;
import uws.service.file.UnsupportedURIProtocolException;
import uws.service.request.RequestParser;
import uws.service.request.UploadFile;

/**
 * <p>Description of an uploaded content specified using the DALI/TAP syntax.</p>
 * 
 * <h3>How to access the upload content?</h3>
 * 
 * <p>
 * 	This parameter is either a reference to a distant content and is then specified by a URI,
 * 	or a pointer to the stored version of a file submitted inline in a HTTP request. In both cases,
 * 	this class lets access the upload content with the function {@link #open()}.
 * </p>
 * 
 * <h3>How to get {@link DALIUpload} objects from HTTP request parameters?</h3>
 * 
 * <p>
 * 	The static function {@link #getDALIUploads(Map, boolean, UWSFileManager)} should be used in order to
 * 	extract the {@link DALIUpload} items specified in a list of request parameters.
 * </p>
 * <p><i>Note:
 * 	It is recommended to provide these parameters as a map generated by a {@link RequestParser}.
 * 	If not, you should ensure that values of the map associated to the "UPLOAD" parameter(s) are {@link String}s, {@link String}[]s,
 * 	{@link DALIUpload}s, {@link DALIUpload}[]s or {@link Object}[] containing {@link String}s and/or {@link DALIUpload}s.
 * 	Besides, the request parameters referenced using the syntax "param:{param-name}" must be instances of only {@link UploadFile}
 * 	or an array of {@link Object}s containing at least one {@link UploadFile} instance (if several are found, just the last one will be used).
 * </i></p>
 * <p>
 * 	Calling this function will also modify a little the given list of parameters by rewriting the "UPLOAD" parameter and
 * 	removing unreferenced uploaded files (from the list and from the file-system).
 * </p>
 * 
 * <h3>Reminder about the "UPLOAD" parameter</h3>
 * 
 * <p>
 * 	The IVOA standards DAL and TAP define both the same special parameter: "UPLOAD" (not case-sensitive).
 * </p>
 * 
 * <p>
 * 	This parameter lists all upload items. A such item can be either an inline file or a reference to a distant file.
 * 	In both cases, it is specified as a URI. The parameter "UPLOAD" sets also a label/name to this item.
 * 	The syntax to use for a single item is the following: "{label},{URI}". Several items can be provided, but there is
 * 	a slight difference between DALI and TAP in the way to do it. DALI says that multiple uploads MUST be done
 * 	by several submit of a single "UPLOAD" parameter with the syntax described above. TAP says that multiple uploads CAN
 * 	be done in one "UPLOAD" parameter by separating each item with a semicolon (;). For instance:
 * </p>
 * <ul>
 * 	<li><b>In TAP:</b> "UPLOAD=tableA,param:foo;tableB,http://..." =&gt; only 1 parameter for 2 uploads</li>
 * 	<li><b>In DALI:</b> "UPLOAD=tableA,param:foo" and "UPLOAD=tableB,http://..." =&gt; 2 parameters, one for each upload</li>
 * </ul>
 * 
 * <p><i>Note:
 * 	The drawback of the TAP method is: what happens when a URI contains a semicolon? URI can indeed contain a such character
 * 	and in this case the parsing becomes more tricky, or even impossible in some cases. In such cases, it is strongly
 * 	recommended to either encode the URI (so the ";" becomes "%3B") or to forbid the TAP syntax. This latter can be
 * 	done by setting the second parameter of {@link #getDALIUploads(Map, boolean, UWSFileManager)} to <i>false</i>.
 * </i></p>
 * 
 * @author Gr&eacute;gory Mantelet (ARI)
 * @version 2.0 (12/2014)
 * @since 2.0
 * 
 * @see RequestParser
 */
public class DALIUpload {

	/** <p>Pointer to the stored version of the file submitted inline in a HTTP request.</p>
	 * <p><i>Note:
	 * 	If NULL, this {@link DALIUpload} is then a "byReference" upload, meaning that its content is distant
	 * 	and can be accessed only with the URI {@link #uri}.
	 * </i></p> */
	public final UploadFile file;

	/** <p>URI toward a distant resource.</p>
	 * <p><i>Note:
	 * 	If NULL, this {@link DALIUpload} corresponds to a file submitted inline in a HTTP request.
	 * 	Its content has then been stored by this service and can be accessed using the pointer {@link #file}.
	 *  </i></p>*/
	public final URI uri;

	/** <p>Name to use in the service to label this upload.</p>
	 * <p><i>Note:
	 * 	In a TAP service, this label is the name of the table to create in the database
	 * 	when creating the corresponding table inside it.
	 * </i></p> */
	public final String label;

	/** The file manager to use when a stream will be opened toward the given URI.
	 * It should know how to access it, because the URI can use a URL scheme (http, https, ftp) but also another scheme
	 * unknown by the library (e.g. ivo, vos). */
	protected final UWSFileManager fileManager;

	/**
	 * <p>Build a {@link DALIUpload} whose the content has been submitted inline in an HTTP request.</p>
	 * 
	 * <p>
	 * 	A such upload has been specified by referencing another HTTP request parameter containing an inline file.
	 * 	The used syntax was then: "{label},param:{param-name}".
	 * </p>
	 * 
	 * @param label	Label of the DALIUpload (i.e. {label} inside an "UPLOAD" parameter value "{label},{URI}").
	 *             	<i>Note: If NULL, the file name will be used as label.</i>
	 * @param file	Pointer to the uploaded file.
	 */
	public DALIUpload(final String label, final UploadFile file){
		if (file == null)
			throw new NullPointerException("Missing UploadFile! => Can not build a DaliUpload instance.");

		this.label = (label == null) ? file.paramName : label;
		this.file = file;
		this.uri = null;
		this.fileManager = null;
	}

	/**
	 * <p>Build a {@link DALIUpload} whose the content is distant and specified by a URI.</p>
	 * 
	 * <p>
	 * 	A such upload has been specified by referencing a URI (whose the scheme is different from "param").
	 * 	The used syntax was then: "{label},{URI}".
	 * </p>
	 * 
	 * @param label			Label of the DALIUpload (i.e. {label} inside an "UPLOAD" parameter value "{label},{URI}"). <i>Note: If NULL, the URI will be used as label.</i>
	 * @param uri			URI toward a distant file. <i><b>The scheme of this URI must be different from "param".</b> This scheme is indeed reserved by the DALI syntax to reference a HTTP request parameter containing an inline file.</i>
	 * @param fileManager	The file manager to use when a stream will be opened toward the given URI. This file manager should know how to access it,
	 *                   	because the URI can use a URL scheme (http, https, ftp) but also another scheme unknown by the library (e.g. ivo, vos).
	 */
	public DALIUpload(final String label, final URI uri, final UWSFileManager fileManager){
		if (uri == null)
			throw new NullPointerException("Missing URI! => Can not build a DaliUpload instance.");
		else if (uri.getScheme() != null && uri.getScheme().equalsIgnoreCase("param"))
			throw new IllegalArgumentException("Wrong URI scheme: \"param\" is reserved to reference a HTTP request parameter! If used, the content of this parameter must be stored in a file, then the parameter must be represented by an UploadFile and integrated into a DALIUpload with the other constructor.");
		else if (uri.getScheme() != null && uri.getScheme().equalsIgnoreCase("file"))
			throw new IllegalArgumentException("Wrong URI scheme: \"file\" is forbidden!");
		else if (fileManager == null)
			throw new NullPointerException("Missing File Manager! => Can not build a DaliUpload instance.");

		this.label = (label == null) ? uri.toString() : label;
		this.uri = uri;
		this.file = null;
		this.fileManager = fileManager;
	}

	/**
	 * Tell whether this upload is actually a reference toward a distant resource.
	 * 
	 * @return	<i>true</i> if this upload is referenced by a URI,
	 *        	<i>false</i> if the upload has been submitted inline in the HTTP request.
	 */
	public boolean isByReference(){
		return (file == null);
	}

	/**
	 * Open a stream to the content of this upload.
	 * 
	 * @return	An InputStream.
	 * 
	 * @throws UnsupportedURIProtocolException	If the URI of this upload item is using a protocol not supported by this service implementation.
	 * @throws IOException				If the stream can not be opened.
	 */
	public InputStream open() throws UnsupportedURIProtocolException, IOException{
		if (file == null)
			return fileManager.openURI(uri);
		else
			return file.open();
	}

	@Override
	public String toString(){
		return label + "," + (file != null ? "param:" + file.paramName : uri.toString());
	}

	/* ****************************** */
	/* EXTRACTION OF DALI/TAP UPLOADS */
	/* ****************************** */

	/** <p>Regular expression of an UPLOAD parameter as defined by DALI (REC-DALI-1.0-20131129).</p>
	 * <p><i>Note:
	 * 	In DALI, multiple uploads must be done by posting several UPLOAD parameters.
	 * 	It is not possible to provide directly a list of parameters as in TAP.
	 * 	However, the advantage of the DALI method is to allow ; in URI (while ; is the
	 * 	parameter separator in TAP).
	 * </i></p> */
	protected static final String DALI_UPLOAD_REGEXP = "[^,]+,\\s*(param:.+|.+)";

	/** <p>Regular expression of an UPLOAD parameter as defined by TAP (REC-TAP-1.0).</p>
	 * <p><i>Note:
	 * 	In TAP, multiple uploads may be done by POSTing only one UPLOAD parameter
	 * 	whose the value is a list of DALI UPLOAD parameters, separated by a ;
	 * </i></p> */
	protected static final String TAP_UPLOAD_REGEXP = DALI_UPLOAD_REGEXP + "(\\s*;\\s*" + DALI_UPLOAD_REGEXP + ")*";

	/**
	 * <p>Get all uploads specified in the DALI parameter "UPLOAD" from the given request parameters.</p>
	 * 
	 * <p><i>Note:
	 * 	This function is case INsensitive for the "UPLOAD" parameter.
	 * </i></p>
	 * <p><b>WARNING:</b>
	 * 	Calling this function modifies the given map ONLY IF the "UPLOAD" parameter (whatever is its case) is found.
	 * 	In such case, the following modifications are applied:
	 * </p>
	 * <ul>
	 * 	<li>
	 * 		All "UPLOAD" parameters will be removed and then added again in the map with their corresponding {@link DALIUpload} item (not any more a String).
	 * 	</li>
	 * 	<li>
	 * 		If <i>allowTAPSyntax</i> is <i>true</i>, several uploads may be specified in the same "UPLOAD" parameter value.
	 * 		For more clarity for the user (once the parameters listed), this list of uploads will be split in the same number of "UPLOAD" parameters.
	 * 		That's to say, there will be only one "UPLOAD" item in the Map, but its value will be an array containing every specified uploads:
	 * 		<i>an array of {@link DALIUpload} objects</i>.
	 * 	</li>
	 * 	<li>
	 * 		If there is at least one "UPLOAD" parameter, all uploaded files (parameters associated with instances of {@link UploadFile}) will be removed
	 * 		from the map (and also from the file system). They are indeed not useful for a DALI service since all interesting uploads have already been
	 * 		listed.
	 * 	</li>
	 * </ul>
	 * 
	 * <p><i>Note:
	 * 	This function can be called several times on the same map. After a first call, this function will just gathers into a List
	 * 	all found {@link DALIUpload} objects. Of course, only uploads specified in the "UPLOAD" parameter(s) will be returned and others will be removed
	 * 	as explained above.
	 * </i></p>
	 * 
	 * <h3>DALI and TAP syntax</h3>
	 * <p>
	 * 	The "UPLOAD" parameter lists all files to consider as uploaded.
	 * 	The syntax for one item is the following: "{name},{uri}", where {uri} is "param:{param-ref}" when the file is provided
	 * 	inline in the parameter named {param-ref}, otherwise, it can be any valid URI (http:..., ftp:..., vos:..., ivo:..., etc...).
	 * </p>
	 * 
	 * <p>
	 * 	The parameter <i>allowTAPSyntax</i> lets switch between the DALI and TAP syntax.
	 * 	The only difference between them, is in the way to list multiple uploads. In TAP, they can be given as a semicolon separated
	 * 	list in a single parameter, whereas in DALI, there must be submitted as several individual parameters. For instance:
	 * </p>
	 * <ul>
	 * 	<li><b>In TAP:</b> "UPLOAD=tableA,param:foo;tableB,http://..." =&gt; only 1 parameter</li>
	 * 	<li><b>In DALI:</b> "UPLOAD=tableA,param:foo" and "UPLOAD=tableB,http://..." =&gt; 2 parameters</li>
	 * </ul>
	 * 
	 * <p><i>Note:
	 * 	Because of the possible presence of a semicolon in a URI (which is also used as separator of uploads in the TAP syntax),
	 * 	there could be a problem while splitting the uploads specified in "UPLOAD". In that case, it is strongly recommended to
	 * 	either encode the URI (in UTF-8) (i.e. ";" becomes "%3B") or to merely restrict the syntax to the DALI one. In this last case,
	 * 	the parameter {@link #allowTAPSyntax} should be set to <i>false</i> and then all parameters should be submitted individually.
	 * </i></p>
	 * 
	 * @param requestParams		All parameters extracted from an HTTP request by a {@link RequestParser}.
	 * @param allowTAPSyntax	<i>true</i> to allow a list of several upload items in one "UPLOAD" parameter value (each item separated by a semicolon),
	 *                      	<i>false</i> to forbid it (and so, multiple upload items shall be submitted individually).
	 * @param fileManager		The file manager to use in order to build a {@link DALIUpload} objects from a URI.
	 *                   		<i>(a link to the file manager will be set in the {@link DALIUpload} object in order to open it
	 *                   		whenever it will asked after its creation)</i>
	 * 
	 * @return	List of all uploads specified with the DALI or TAP syntax.
	 * 
	 * @throws TAPException	If the syntax of an "UPLOAD" parameter is wrong.
	 * 
	 * @see {@link RequestParser#parse(javax.servlet.http.HttpServletRequest)}
	 */
	public final static List<DALIUpload> getDALIUploads(final Map<String,Object> requestParams, final boolean allowTAPSyntax, final UWSFileManager fileManager) throws TAPException{

		// 1. Get all "UPLOAD" parameters and build/get their corresponding DALIUpload(s):
		ArrayList<DALIUpload> uploads = new ArrayList<DALIUpload>(3);
		ArrayList<String> usedFiles = new ArrayList<String>(3);
		Iterator<Map.Entry<String,Object>> it = requestParams.entrySet().iterator();
		Map.Entry<String,Object> entry;
		Object value;
		while(it.hasNext()){
			entry = it.next();

			// If the parameter is an "UPLOAD" one:
			if (entry.getKey() != null && entry.getKey().toLowerCase().equals(TAPJob.PARAM_UPLOAD)){
				// get its value:
				value = entry.getValue();

				if (value != null){
					// CASE DALIUpload: just add the upload item inside the list:
					if (value instanceof DALIUpload){
						DALIUpload upl = (DALIUpload)value;
						uploads.add(upl);
						if (!upl.isByReference())
							usedFiles.add(upl.file.paramName);
					}
					// CASE String: it must be parsed and transformed into a DALIUpload item which will be then added inside the list:
					else if (value instanceof String)
						fetchDALIUploads(uploads, usedFiles, (String)value, requestParams, allowTAPSyntax, fileManager);

					// CASE Array: 
					else if (value.getClass().isArray()){
						Object[] objects = (Object[])value;
						for(Object o : objects){
							if (o != null){
								if (o instanceof DALIUpload)
									uploads.add((DALIUpload)o);
								else if (o instanceof String)
									fetchDALIUploads(uploads, usedFiles, (String)o, requestParams, allowTAPSyntax, fileManager);
							}
						}
					}
				}

				// remove this "UPLOAD" parameter ; if it was not NULL, it will be added again in the map but as DALIUpload item(s) after this loop:
				it.remove();
			}
		}

		// 2. Remove all other files of the request parameters ONLY IF there was a not-NULL "UPLOAD" parameter:
		if (uploads.size() > 0){
			it = requestParams.entrySet().iterator();
			while(it.hasNext()){
				entry = it.next();
				value = entry.getValue();
				if (value == null)
					it.remove();
				else if (value instanceof UploadFile && !usedFiles.contains(entry.getKey())){
					try{
						((UploadFile)value).deleteFile();
					}catch(IOException ioe){}
					it.remove();
				}else if (value.getClass().isArray()){
					Object[] objects = (Object[])value;
					int cnt = objects.length;
					for(int i = 0; i < objects.length; i++){
						if (objects[i] == null){
							objects[i] = null;
							cnt--;
						}else if (objects[i] instanceof UploadFile && !usedFiles.contains(entry.getKey())){
							try{
								((UploadFile)objects[i]).deleteFile();
							}catch(IOException ioe){}
							objects[i] = null;
							cnt--;
						}
					}
					if (cnt == 0)
						it.remove();
				}
			}
		}

		// 3. Re-add a new "UPLOAD" parameter gathering all extracted DALI Uploads:
		if (uploads.size() > 0)
			requestParams.put("UPLOAD", uploads.toArray(new DALIUpload[uploads.size()]));

		return uploads;
	}

	/**
	 * <p>Fetch all uploads specified in the DALI/TAP "UPLOAD" parameter.
	 * The fetched {@link DALIUpload}s are added in the given {@link ArrayList}.</p>
	 * 
	 * <p><i>Note: A DALI upload can be either a URI or an inline file (specified as "param:{param-ref}").</i></p>
	 * 
	 * @param uploads			List of {@link DALIUpload}s. <b>to update</b>.
	 * @param usedFiles			List of the the names of the referenced file parameters. <b>to update</b>.
	 * @param uploadParam		Value of the "UPLOAD" parameter.
	 * @param parameters		List of all extracted parameters (including {@link UploadFile}(s)).
	 * @param allowTAPSyntax	<i>true</i> to allow a list of several upload items in one "UPLOAD" parameter value (each item separated by a semicolon),
	 *                      	<i>false</i> to forbid it (and so, multiple upload items shall be submitted individually).
	 * @param fileManager		The file manager to use in order to build a {@link DALIUpload} objects from a URI.
	 *                   		<i>(a link to the file manager will be set in the {@link DALIUpload} object in order to open it
	 *                   		whenever it will asked after its creation)</i>
	 * 
	 * @return	The corresponding {@link DALIUpload} objects.
	 * 
	 * @throws TAPException	If the syntax of the given "UPLOAD" parameter is incorrect.
	 */
	protected static void fetchDALIUploads(final ArrayList<DALIUpload> uploads, final ArrayList<String> usedFiles, String uploadParam, final Map<String,Object> parameters, final boolean allowTAPSyntax, final UWSFileManager fileManager) throws TAPException{
		if (uploadParam == null || uploadParam.trim().length() <= 0)
			return;

		// TAP SYNTAX (list of DALI UPLOAD items, separated by a semicolon):
		if (allowTAPSyntax && uploadParam.matches("([^,]+,.+);([^,]+,.+)")){
			Pattern p = Pattern.compile("([^,]+,.+);([^,]+,.+)");
			Matcher m = p.matcher(uploadParam);
			while(m != null && m.matches()){
				// Fetch the last UPLOAD item:
				DALIUpload upl = fetchDALIUpload(m.group(2), parameters, fileManager);
				uploads.add(upl);
				if (!upl.isByReference())
					usedFiles.add(upl.file.paramName);

				// Prepare the fetching of the other DALI parameters:
				if (m.group(1) != null)
					m = p.matcher(uploadParam = m.group(1));
			}
		}

		// DALI SYNTAX (only one UPLOAD item):
		if (uploadParam.matches("[^,]+,.+")){
			// Fetch the single UPLOAD item:
			DALIUpload upl = fetchDALIUpload(uploadParam, parameters, fileManager);
			uploads.add(upl);
			if (!upl.isByReference())
				usedFiles.add(upl.file.paramName);
		}

		// /!\ INCORRECT SYNTAX /!\
		else
			throw new TAPException("Wrong DALI syntax for the parameter UPLOAD \"" + uploadParam + "\"!", UWSException.BAD_REQUEST);
	}

	/**
	 * Fetch the single upload item (a pair with the syntax: "{label},{URI}".
	 * 
	 * @param uploadParam	Value of the "UPLOAD" parameter. <i>A single upload item is expected ; that's to say something like "{label},{URI}".</i>
	 * @param parameters	List of extracted parameters. The fetched LOB must be added as a new parameter in this map. <b>MUST not be NULL</b>
	 * @param fileManager	The file manager to use in order to build a {@link DALIUpload} objects from a URI.
	 *                   	<i>(a link to the file manager will be set in the {@link DALIUpload} object in order to open it
	 *                   	whenever it will asked after its creation)</i>
	 * 
	 * @return	The corresponding {@link DALIUpload} object.
	 * 
	 * @throws TAPException	If the syntax of the given "UPLOAD" parameter is incorrect.
	 * 
	 * @see #parseDALIParam(String)
	 * @see #buildDALIUpload(String, String, Map, UWSFileManager)
	 */
	protected static DALIUpload fetchDALIUpload(final String uploadParam, final Map<String,Object> parameters, final UWSFileManager fileManager) throws TAPException{
		if (uploadParam.matches("[^,]+,.+")){
			// Check and extract the pair parts ([0]=label, [1]=URI):
			String[] parts = parseDALIParam(uploadParam);

			// Build the corresponding DALIUpload:
			return buildDALIUpload(parts[0], parts[1], parameters, fileManager);
		}else
			throw new TAPException("Wrong DALI syntax for the parameter UPLOAD \"" + uploadParam + "\"!", UWSException.BAD_REQUEST);
	}

	/**
	 * <p>Extract the two parts (label and URI) of the given DALI parameter, and then, check their syntax.</p>
	 * 
	 * <p><i><b>Important note:</b>
	 * 	It MUST be ensured before calling this function that the given DALI parameter is not NULL
	 * 	and contains at least one comma (,).
	 * </i></p>
	 * 
	 * <p>
	 * 	The first comma found in the given string will be the separator of the two parts
	 * 	of the given DALI parameter: {label},{URI}
	 * </p>
	 * 
	 * <p>
	 * 	The label part - {label} - must start with one letter and may be followed by a letter,
	 * 	a digit or an underscore. The corresponding regular expression is: [a-zA-Z][a-zA-Z0-9_]*
	 * </p>
	 * 
	 * <p>
	 * 	The URI part - {URI} - must start with a scheme, followed by a colon (:) and then by several characters
	 * 	(no restriction). A scheme must start with one letter and may be followed by a letter,
	 * 	a digit, a plus (+), a dot (.) or an hyphen/minus (-). The corresponding regular expression is:
	 * 	[a-zA-Z][a-zA-Z0-9\+\.-]*
	 * </p>
	 * 
	 * @param definition	MUST BE A PAIR label,value
	 * 
	 * @return	An array of exactly 2 items: [0]=upload label/name, [1]=an URI.	<i>(note: the special DALI syntax "param:..." is also a valid URI)</i>
	 * 
	 * @throws TAPException	If the given upload definition is not following the valid DALI syntax.
	 */
	protected static String[] parseDALIParam(final String definition) throws TAPException{
		// Locate the separator:
		int sep = definition.indexOf(',');
		if (sep <= 0)
			throw new TAPException("A DALI parameter must be a pair whose the items are separated by a colon!", UWSException.INTERNAL_SERVER_ERROR);

		// Extract the two parts: {label},{uri}
		String[] parts = new String[]{definition.substring(0, sep),definition.substring(sep + 1)};

		// Check the label:
		if (!parts[0].matches("[a-zA-Z][a-zA-Z0-9_]*"))
			throw new TAPException("Wrong uploaded item name syntax: \"" + parts[0] + "\"! An uploaded item must have a label with the syntax: [a-zA-Z][a-zA-Z0-9_]*.", UWSException.BAD_REQUEST);
		// Check the URI:
		else if (!parts[1].matches("[a-zA-Z][a-zA-Z0-9\\+\\.\\-]*:.+"))
			throw new TAPException("Bad URI syntax: \"" + parts[1] + "\"! A URI must start with: \"<scheme>:\", where <scheme>=\"[a-zA-Z][a-zA-Z0-9+.-]*\".", UWSException.BAD_REQUEST);

		return parts;
	}

	/**
	 * <p>Build a {@link DALIUpload} corresponding to the specified URI.</p>
	 * 
	 * <p>
	 * 	If the URI starts, case-insensitively, with "param:", it is then a reference to another request parameter containing a file content.
	 * 	In this case, the file content has been already stored inside a local file and represented by an {@link UploadFile} instance in the map.
	 * </p>
	 * 
	 * <p>
	 * 	If the URI does not start with "param:", the DALI upload is considered as a reference to a distant file which can be accessed using this URI.
	 * 	Any URI scheme is allowed here, but the given file manager should be able to interpret it and open a stream toward the referenced resource
	 * 	whenever it will be asked.
	 * </p>
	 * 
	 * <p><i>Note:
	 * 	If the URI is not a parameter reference (i.e. started by "param:"), it will be decoded using {@link URLDecoder#decode(String, String)}
	 * 	(character encoding: UTF-8).
	 * </i></p>
	 * 
	 * @param label			Label of the {@link DALIUpload} to build.
	 * @param uri			URI of the LOB. <b>MUST be NOT-NULL</b>
	 * @param parameters	All parameters extracted from an HTTP request by a {@link RequestParser}.
	 * @param fileManager	The file manager to use in order to build a {@link DALIUpload} objects from a URI.
	 *                   	<i>(a link to the file manager will be set in the {@link DALIUpload} object in order to open it
	 *                   	whenever it will asked after its creation)</i>
	 * 
	 * @return	The corresponding {@link DALIUpload} object.
	 * 
	 * @throws TAPException	If the parameter reference is broken or if the given URI has a wrong syntax.
	 */
	protected final static DALIUpload buildDALIUpload(final String label, String uri, final Map<String,Object> parameters, final UWSFileManager fileManager) throws TAPException{
		// FILE case:
		if (uri.toLowerCase().startsWith("param:")){

			// get the specified parameter name:
			uri = uri.substring(6);

			// get the corresponding file:
			Object obj = parameters.get(uri);

			/* a map value can be an array of objects in case several parameters have the same name ;
			 * in this case, we just keep the last instance of UploadFile: */
			if (obj != null && obj.getClass().isArray()){
				Object[] objects = (Object[])obj;
				obj = null;
				for(Object o : objects){
					if (o != null && o instanceof UploadFile)
						obj = o;
				}
			}

			// ensure the type of the retrieved parameter is correct:
			if (obj == null)
				throw new TAPException("Missing file parameter to upload: \"" + uri + "\"!", UWSException.BAD_REQUEST);
			else if (!(obj instanceof UploadFile))
				throw new TAPException("Incorrect parameter type \"" + uri + "\": a file was expected!", UWSException.BAD_REQUEST);

			// build the LOB:
			return new DALIUpload(label, (UploadFile)obj);
		}

		// URI case:
		else{
			// extract the URI as it is given:
			uri = uri.trim();
			if (uri.toLowerCase().startsWith("file:"))
				throw new TAPException("Wrong URI scheme in the upload specification labeled \"" + label + "\": \"file\" is forbidden!", UWSException.BAD_REQUEST);
			// decode it in case there is any illegal character:
			try{
				uri = URLDecoder.decode(uri, "UTF-8");
			}catch(UnsupportedEncodingException uee){}
			try{
				// build the LOB:
				return new DALIUpload(label, new URI(uri), fileManager);
			}catch(URISyntaxException e){
				throw new TAPException("Incorrect URI syntax: \"" + uri + "\"!", UWSException.BAD_REQUEST);
			}
		}
	}

}
