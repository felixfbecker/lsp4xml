/**
 *  Copyright (c) 2018 Angelo ZERR
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  Contributors:
 *  Angelo Zerr <angelo.zerr@gmail.com> - initial API and implementation
 */
package org.eclipse.lsp4xml.services;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4xml.commons.BadLocationException;
import org.eclipse.lsp4xml.commons.TextDocument;
import org.eclipse.lsp4xml.dom.Element;
import org.eclipse.lsp4xml.dom.Node;
import org.eclipse.lsp4xml.dom.XMLDocument;
import org.eclipse.lsp4xml.internal.parser.Scanner;
import org.eclipse.lsp4xml.internal.parser.ScannerState;
import org.eclipse.lsp4xml.internal.parser.TokenType;
import org.eclipse.lsp4xml.internal.parser.XMLScanner;
import org.eclipse.lsp4xml.services.extensions.CompletionSettings;
import org.eclipse.lsp4xml.services.extensions.ICompletionParticipant;
import org.eclipse.lsp4xml.services.extensions.ICompletionRequest;
import org.eclipse.lsp4xml.services.extensions.ICompletionResponse;
import org.eclipse.lsp4xml.services.extensions.XMLExtensionsRegistry;
import org.eclipse.lsp4xml.settings.XMLFormattingOptions;

/**
 * XML completions support.
 *
 */
class XMLCompletions {

	private static final Logger LOGGER = Logger.getLogger(XMLCompletions.class.getName());
	private static final String cdata = "![CDATA[]]";
	private static final Pattern regionCompletionRegExpr = Pattern.compile("^(\\s*)(<(!(-(-\\s*(#\\w*)?)?)?)?)?$");

	private final XMLExtensionsRegistry extensionsRegistry;

	public XMLCompletions(XMLExtensionsRegistry extensionsRegistry) {
		this.extensionsRegistry = extensionsRegistry;
	}

	public CompletionList doComplete(XMLDocument xmlDocument, Position position, CompletionSettings completionSettings,
			XMLFormattingOptions formattingSettings) {
		CompletionResponse completionResponse = new CompletionResponse();
		CompletionRequest completionRequest = null;
		try {
			completionRequest = new CompletionRequest(xmlDocument, position, completionSettings, formattingSettings);
		} catch (BadLocationException e) {
			LOGGER.log(Level.SEVERE, "Creation of CompletionRequest failed", e);
			return completionResponse;
		}

		int offset = completionRequest.getOffset();
		Node node = completionRequest.getNode();

		String text = xmlDocument.getText();
		Scanner scanner = XMLScanner.createScanner(text, node.start);
		String currentTag = "";
		completionRequest.setCurrentAttributeName(null);
		TokenType token = scanner.scan();
		while (token != TokenType.EOS && scanner.getTokenOffset() <= offset) {
			switch (token) {
			case StartTagOpen:
				if (scanner.getTokenEnd() == offset) {
					int endPos = scanNextForEndPos(offset, scanner, TokenType.StartTag);
					collectTagSuggestions(offset, endPos, completionRequest, completionResponse);
					return completionResponse;
				}
				break;
			case StartTag:
				if (scanner.getTokenOffset() <= offset && offset <= scanner.getTokenEnd()) {
					collectOpenTagSuggestions(scanner.getTokenOffset(), scanner.getTokenEnd(), completionRequest,
							completionResponse);
					return completionResponse;
				}
				currentTag = scanner.getTokenText();
				break;
			case AttributeName:
				if (scanner.getTokenOffset() <= offset && offset <= scanner.getTokenEnd()) {
					collectAttributeNameSuggestions(scanner.getTokenOffset(), scanner.getTokenEnd(), completionRequest,
							completionResponse);
					return completionResponse;
				}
				completionRequest.setCurrentAttributeName(scanner.getTokenText());
				break;
			case DelimiterAssign:
				if (scanner.getTokenEnd() == offset) {
					int endPos = scanNextForEndPos(offset, scanner, TokenType.AttributeValue);
					collectAttributeValueSuggestions(offset, endPos, completionRequest, completionResponse);
					return completionResponse;
				}
				break;
			case AttributeValue:
				if (scanner.getTokenOffset() <= offset && offset <= scanner.getTokenEnd()) {
					collectAttributeValueSuggestions(scanner.getTokenOffset(), scanner.getTokenEnd(), completionRequest,
							completionResponse);
					return completionResponse;
				}
				break;
			case Whitespace:
				if (offset <= scanner.getTokenEnd()) {
					switch (scanner.getScannerState()) {
					case AfterOpeningStartTag:
						int startPos = scanner.getTokenOffset();
						int endTagPos = scanNextForEndPos(offset, scanner, TokenType.StartTag);
						collectTagSuggestions(startPos, endTagPos, completionRequest, completionResponse);
						return completionResponse;
					case WithinTag:
					case AfterAttributeName:
						collectAttributeNameSuggestions(scanner.getTokenEnd(), completionRequest, completionResponse);
						return completionResponse;
					case BeforeAttributeValue:
						collectAttributeValueSuggestions(scanner.getTokenEnd(), offset, completionRequest,
								completionResponse);
						return completionResponse;
					case AfterOpeningEndTag:
						collectCloseTagSuggestions(scanner.getTokenOffset() - 1, false, offset, completionRequest,
								completionResponse);
						return completionResponse;
					case WithinContent:
						collectInsideContent(completionRequest, completionResponse);
						return completionResponse;
					}
				}
				break;
			case EndTagOpen:
				if (offset <= scanner.getTokenEnd()) {
					int afterOpenBracket = scanner.getTokenOffset() + 1;
					int endOffset = scanNextForEndPos(offset, scanner, TokenType.EndTag);
					collectCloseTagSuggestions(afterOpenBracket, false, endOffset, completionRequest,
							completionResponse);
					return completionResponse;
				}
				break;
			case EndTag:
				if (offset <= scanner.getTokenEnd()) {
					int start = scanner.getTokenOffset() - 1;
					while (start >= 0) {
						char ch = text.charAt(start);
						if (ch == '/') {
							collectCloseTagSuggestions(start, false, scanner.getTokenEnd(), completionRequest,
									completionResponse);
							return completionResponse;
						} else if (!isWhiteSpace(ch)) {
							break;
						}
						start--;
					}
				}
				break;
			case StartTagClose:
				if (offset <= scanner.getTokenEnd()) {
					if (currentTag != null && currentTag.length() > 0) {
						collectAutoCloseTagSuggestion(scanner.getTokenEnd(), currentTag, completionRequest,
								completionResponse);
						return completionResponse;
					}
				}
				break;
			case EndTagClose:
				if (offset <= scanner.getTokenEnd()) {
					if (currentTag != null && currentTag.length() > 0) {
						collectInsideContent(completionRequest, completionResponse);
						return completionResponse;
					}
				}
				break;
			case Content:
				if (offset <= scanner.getTokenEnd()) {
					collectInsideContent(completionRequest, completionResponse);
					return completionResponse;
				}
				break;
			default:
				if (offset <= scanner.getTokenEnd()) {
					return completionResponse;
				}
				break;
			}
			token = scanner.scan();
		}

		return completionResponse;
	}

	public String doTagComplete(XMLDocument xmlDocument, Position position) {
		int offset;
		try {
			offset = xmlDocument.offsetAt(position);
		} catch (BadLocationException e) {
			LOGGER.log(Level.SEVERE, "doTagComplete failed", e);
			return null;
		}
		if (offset <= 0) {
			return null;
		}
		char c = xmlDocument.getText().charAt(offset - 1);
		if (c == '>') {
			Node node = xmlDocument.findNodeBefore(offset);
			if (node != null && node.tag != null && !isEmptyElement(node.tag) && node.start < offset
					&& (node.endTagStart == null || node.endTagStart > offset)) {
				Scanner scanner = XMLScanner.createScanner(xmlDocument.getText(), node.start);
				TokenType token = scanner.scan();
				while (token != TokenType.EOS && scanner.getTokenEnd() <= offset) {
					if (token == TokenType.StartTagClose && scanner.getTokenEnd() == offset) {
						return "$0</" + node.tag + ">";
					}
					token = scanner.scan();
				}
			}
		} else if (c == '/') {
			Node node = xmlDocument.findNodeBefore(offset);
			while (node != null && node.isClosed()) {
				node = node.parent;
			}
			if (node != null && node.tag != null) {
				Scanner scanner = XMLScanner.createScanner(xmlDocument.getText(), node.start);
				TokenType token = scanner.scan();
				while (token != TokenType.EOS && scanner.getTokenEnd() <= offset) {
					if (token == TokenType.EndTagOpen && scanner.getTokenEnd() == offset) {
						return node.tag + ">";
					}
					token = scanner.scan();
				}
			}
		}
		return null;
	}

	// ---------------- Tags completion

	// Tags that look like '<'
	private void collectTagSuggestions(int tagStart, int tagEnd, CompletionRequest completionRequest,
			CompletionResponse completionResponse) {
		collectOpenTagSuggestions(tagStart, tagEnd, completionRequest, completionResponse);
		collectCloseTagSuggestions(tagStart, true, tagEnd, completionRequest, completionResponse);
	}

	private void collectOpenTagSuggestions(int afterOpenBracket, int tagNameEnd, CompletionRequest completionRequest,
			CompletionResponse completionResponse) {
		try {
			Range replaceRange = getReplaceRange(afterOpenBracket, tagNameEnd, completionRequest);
			collectOpenTagSuggestions(replaceRange, true, completionRequest, completionResponse);
		} catch (BadLocationException e) {
			LOGGER.log(Level.SEVERE, "While performing Completions the provided offset was a BadLocation", e);
			return;
		}
	}

	private void collectOpenTagSuggestions(Range replaceRange, boolean hasOpenTag, CompletionRequest completionRequest,
			CompletionResponse completionResponse) {
		completionRequest.setReplaceRange(replaceRange);
		completionRequest.setOpenTag(hasOpenTag);
		if (!completionRequest.getXMLDocument().hasGrammar()) {
			// no grammar, collect similar tags from the parent node
			Node parentNode = completionRequest.getParentNode();
			Set<String> seenElements = new HashSet<>();
			if (parentNode != null && parentNode.isElement() && parentNode.hasChildren()) {
				parentNode.getChildren().forEach(node -> {
					if (!node.isElement()) {
						return;
					}
					Element element = (Element) node;
					String tag = element.getTagName();
					if (seenElements.contains(tag)) {
						return;
					}
					seenElements.add(tag);
					CompletionItem item = new CompletionItem();
					item.setLabel(tag);
					item.setKind(CompletionItemKind.Property);
					item.setFilterText(tag);
					StringBuilder xml = new StringBuilder();
					if (!hasOpenTag) {
						xml.append("<");
					}
					xml.append(tag);
					if (element.isSelfClosed()) {
						xml.append(" />");
					} else {
						xml.append(">");
						if (completionRequest.getCompletionSettings().isCompletionSnippetsSupported()) {
							xml.append("$0");
						}
						xml.append("</").append(tag).append(">");
					}
					item.setTextEdit(new TextEdit(replaceRange, xml.toString()));
					item.setInsertTextFormat(InsertTextFormat.Snippet);

					completionResponse.addCompletionItem(item);
				});
			}
		}
		for (ICompletionParticipant participant : getCompletionParticipants()) {
			try {
				participant.onTagOpen(completionRequest, completionResponse);
			} catch (Exception e) {
				LOGGER.log(Level.SEVERE, "While performing ICompletionParticipant#onTagOpen", e);
			}
		}
	}

	private void collectCloseTagSuggestions(int afterOpenBracket, boolean inOpenTag, int tagNameEnd,
			CompletionRequest completionRequest, CompletionResponse completionResponse) {
		try {
			Range range = getReplaceRange(afterOpenBracket, tagNameEnd, completionRequest);
			String text = completionRequest.getXMLDocument().getText();
			String closeTag = isFollowedBy(text, tagNameEnd, ScannerState.WithinEndTag, TokenType.EndTagClose) ? ""
					: ">";
			Node curr = completionRequest.getNode();
			if (inOpenTag) {
				curr = curr.parent; // don't suggest the own tag, it's not yet open
			}
			int offset = completionRequest.getOffset();
			while (curr != null) {
				String tag = curr.tag;
				if (tag != null && (!curr.isClosed() || curr.endTagStart != null && (curr.endTagStart > offset))) {
					CompletionItem item = new CompletionItem();
					item.setLabel("/" + tag);
					item.setKind(CompletionItemKind.Property);
					item.setFilterText("/" + tag + closeTag);
					item.setTextEdit(new TextEdit(range, "/" + tag + closeTag));
					item.setInsertTextFormat(InsertTextFormat.PlainText);

					String startIndent = getLineIndent(curr.start, text);
					String endIndent = getLineIndent(afterOpenBracket - 1, text);
					if (startIndent != null && endIndent != null && !startIndent.equals(endIndent)) {
						String insertText = startIndent + "</" + tag + closeTag;
						item.setTextEdit(new TextEdit(
								getReplaceRange(afterOpenBracket - 1 - endIndent.length(), offset, completionRequest),
								insertText));
						item.setFilterText(endIndent + "</" + tag + closeTag);
					}
					completionResponse.addCompletionItem(item);
					return;
				}
				curr = curr.parent;
			}
			if (inOpenTag) {
				return;
			}

		} catch (BadLocationException e) {
			LOGGER.log(Level.SEVERE, "While performing Completions the provided offset was a BadLocation", e);
		}
	}

	private void collectAutoCloseTagSuggestion(int tagCloseEnd, String tag, CompletionRequest request,
			CompletionResponse response) {
		Position pos;
		try {
			XMLDocument document = request.getXMLDocument();
			pos = document.positionAt(tagCloseEnd);
		} catch (BadLocationException e) {
			LOGGER.log(Level.SEVERE, "While performing Completions the provided offset was a BadLocation", e);
			return;
		}
		CompletionItem item = new CompletionItem();
		item.setLabel("</" + tag + ">");
		item.setKind(CompletionItemKind.Property);
		item.setFilterText("</" + tag + ">");
		item.setTextEdit(new TextEdit(new Range(pos, pos), "$0</" + tag + ">"));
		item.setInsertTextFormat(InsertTextFormat.Snippet);
		response.addCompletionItem(item);
	}

	private void collectInsideContent(CompletionRequest request, CompletionResponse response) {
		Range tagNameRange = request.getXMLDocument().getElementNameRangeAt(request.getOffset());
		if (tagNameRange != null) {
			collectOpenTagSuggestions(tagNameRange, false, request, response);
		}
		// Participant completion on XML content
		for (ICompletionParticipant participant : getCompletionParticipants()) {
			try {
				participant.onXMLContent(request, response);
			} catch (Exception e) {
				LOGGER.log(Level.SEVERE, "While performing ICompletionParticipant#onXMLContent", e);
			}
		}
		collectionRegionProposals(request, response);
		collectCharacterEntityProposals(request, response);
	}

	/**
	 * Collect completion inside comments.
	 * 
	 * @param request
	 * @param response
	 */
	private void collectInsideComment(ICompletionRequest request, ICompletionResponse response) {
		collectionRegionProposals(request, response);
	}

	private void collectionRegionProposals(ICompletionRequest request, ICompletionResponse response) {
		// Completion for #region
		try {
			int offset = request.getOffset();
			TextDocument document = request.getXMLDocument().getTextDocument();
			Position pos = document.positionAt(offset);
			String lineText = document.lineText(pos.getLine());
			String lineUntilPos = lineText.substring(0, pos.getCharacter());
			Matcher match = regionCompletionRegExpr.matcher(lineUntilPos);
			if (match.find()) {
				Range range = new Range(new Position(pos.getLine(), match.regionStart()), pos);

				CompletionItem beginProposal = new CompletionItem("#region");
				beginProposal.setTextEdit(new TextEdit(range, "<!-- #region $1-->"));
				beginProposal.setDocumentation("Folding Region Start");
				beginProposal.setFilterText(match.group());
				beginProposal.setSortText("za");
				beginProposal.setKind(CompletionItemKind.Snippet);
				beginProposal.setInsertTextFormat(InsertTextFormat.Snippet);
				response.addCompletionAttribute(beginProposal);

				CompletionItem endProposal = new CompletionItem("#endregion");
				endProposal.setKind(CompletionItemKind.Snippet);
				endProposal.setTextEdit(new TextEdit(range, "<!-- #endregion-->"));
				endProposal.setDocumentation("Folding Region End");
				endProposal.setFilterText(match.group());
				endProposal.setSortText("zb");
				response.addCompletionAttribute(endProposal);
			}
		} catch (BadLocationException e) {
			LOGGER.log(Level.SEVERE, "While performing collectRegionCompletion", e);
		}
	}

	private void collectCharacterEntityProposals(ICompletionRequest request, ICompletionResponse response) {
		// character entities
		/*
		 * int offset = request.getOffset(); Position position = request.getPosition();
		 * int k = offset - 1; int characterStart = position.getCharacter(); char ch =
		 * request.getDocument().getText().charAt(characterStart); while (k >= 0 &&
		 * (Character.isLetter(ch) || Character.isDigit(ch))) { k--; characterStart--; }
		 * if (k >= 0 && text[k] == '&') { let range =
		 * Range.create(Position.create(position.line, characterStart - 1), position);
		 * for (String entity : entities) { if (endsWith(entity, ';')) { String label =
		 * '&' + entity; result.items.push({ label, kind: CompletionItemKind.Keyword,
		 * documentation: localize('entity.propose', `Character entity representing
		 * '${entities[entity]}'`), textEdit: TextEdit.replace(range, label),
		 * insertTextFormat: InsertTextFormat.PlainText }); } } }
		 */
	}

	private void collectAttributeNameSuggestions(int nameStart, CompletionRequest completionRequest,
			CompletionResponse completionResponse) {
		collectAttributeNameSuggestions(nameStart, completionRequest.getOffset(), completionRequest,
				completionResponse);
	}

	private void collectAttributeNameSuggestions(int nameStart, int nameEnd, CompletionRequest completionRequest,
			CompletionResponse completionResponse) {
		int replaceEnd = completionRequest.getOffset();
		String text = completionRequest.getXMLDocument().getText();
		while (replaceEnd < nameEnd && text.charAt(replaceEnd) != '<') { // < is a valid attribute name character, but
																			// we rather assume the attribute name ends.
																			// See #23236.
			replaceEnd++;
		}
		try {
			Range range = getReplaceRange(nameStart, replaceEnd, completionRequest);
			String value = isFollowedBy(text, nameEnd, ScannerState.AfterAttributeName, TokenType.DelimiterAssign) ? ""
					: "=\"$1\"";
			for (ICompletionParticipant participant : getCompletionParticipants()) {
				participant.onAttributeName(value, range, completionRequest, completionResponse);
			}
		} catch (BadLocationException e) {
			LOGGER.log(Level.SEVERE, "While performing Completions, getReplaceRange() was given a bad Offset location",
					e);
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, "While performing ICompletionParticipant#onAttributeName", e);
		}
	}

	private void collectAttributeValueSuggestions(int valueStart, int valueEnd, CompletionRequest completionRequest,
			CompletionResponse completionResponse) {
		Range range = null;
		boolean addQuotes = false;
		String valuePrefix;
		int offset = completionRequest.getOffset();
		String text = completionRequest.getXMLDocument().getText();
		if (offset > valueStart && offset <= valueEnd && isQuote(text.charAt(valueStart))) {
			// inside quoted attribute
			int valueContentStart = valueStart + 1;
			int valueContentEnd = valueEnd;
			// valueEnd points to the char after quote, which encloses the replace range
			if (valueEnd > valueStart && text.charAt(valueEnd - 1) == text.charAt(valueStart)) {
				valueContentEnd--;
			}
			int wsBefore = getWordStart(text, offset, valueContentStart);
			int wsAfter = getWordEnd(text, offset, valueContentEnd);
			try {
				range = getReplaceRange(wsBefore, wsAfter, completionRequest);
			} catch (BadLocationException e) {
				LOGGER.log(Level.SEVERE,
						"While performing Completions, getReplaceRange() was given a bad Offset location", e);
			}
			valuePrefix = offset >= valueContentStart && offset <= valueContentEnd
					? text.substring(valueContentStart, offset)
					: "";
			addQuotes = false;
		} else {
			try {
				range = getReplaceRange(valueStart, valueEnd, completionRequest);
			} catch (BadLocationException e) {
				LOGGER.log(Level.SEVERE,
						"While performing Completions, getReplaceRange() was given a bad Offset location", e);
			}
			valuePrefix = text.substring(valueStart, offset);
			addQuotes = true;
		}

		Collection<ICompletionParticipant> completionParticipants = getCompletionParticipants();
		if (completionParticipants.size() > 0) {
			try {
				Range fullRange = getReplaceRange(valueStart, valueEnd, completionRequest);
				for (ICompletionParticipant participant : completionParticipants) {
					participant.onAttributeValue(valuePrefix, fullRange, addQuotes, completionRequest,
							completionResponse);
				}
			} catch (BadLocationException e) {
				LOGGER.log(Level.SEVERE,
						"While performing Completions, getReplaceRange() was given a bad Offset location", e);
			} catch (Exception e) {
				LOGGER.log(Level.SEVERE, "While performing ICompletionParticipant#onAttributeValue", e);
			}
		}
	}

	private static int scanNextForEndPos(int offset, Scanner scanner, TokenType nextToken) {
		if (offset == scanner.getTokenEnd()) {
			TokenType token = scanner.scan();
			if (token == nextToken && scanner.getTokenOffset() == offset) {
				return scanner.getTokenEnd();
			}
		}
		return offset;
	}

	/**
	 * Returns list of {@link ICompletionParticipant}.
	 * 
	 * @return list of {@link ICompletionParticipant}.
	 */
	private Collection<ICompletionParticipant> getCompletionParticipants() {
		return extensionsRegistry.getCompletionParticipants();
	}

	// Utilities class.

	private static boolean isQuote(char c) {
		return c == '\'' || c == '"';
	}

	private static boolean isWhiteSpace(char ch) {
		return ch == ' ';
	}

	private static boolean isFollowedBy(String s, int offset, ScannerState intialState, TokenType expectedToken) {
		Scanner scanner = XMLScanner.createScanner(s, offset, intialState);
		TokenType token = scanner.scan();
		while (token == TokenType.Whitespace) {
			token = scanner.scan();
		}
		return token == expectedToken;
	}

	private static int getWordStart(String s, int offset, int limit) {
		while (offset > limit && !isWhiteSpace(s.charAt(offset - 1))) {
			offset--;
		}
		return offset;
	}

	private static int getWordEnd(String s, int offset, int limit) {
		while (offset < limit && !isWhiteSpace(s.charAt(offset))) {
			offset++;
		}
		return offset;
	}

	private static Range getReplaceRange(int replaceStart, int replaceEnd, ICompletionRequest context)
			throws BadLocationException {
		int offset = context.getOffset();
		if (replaceStart > offset) {
			replaceStart = offset;
		}
		XMLDocument document = context.getXMLDocument();
		return new Range(document.positionAt(replaceStart), document.positionAt(replaceEnd));
	}

	private static String getLineIndent(int offset, String text) {
		int start = offset;
		while (start > 0) {
			char ch = text.charAt(start - 1);
			if ("\n\r".indexOf(ch) >= 0) {
				return text.substring(start, offset);
			}
			if (!isWhiteSpace(ch)) {
				return null;
			}
			start--;
		}
		return text.substring(0, offset);
	}

	private boolean isEmptyElement(String tag) {
		// TODO Auto-generated method stub
		return false;
	}

}
