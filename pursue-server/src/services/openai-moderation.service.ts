import { logger } from '../utils/logger.js';
import { ApplicationError } from '../middleware/errorHandler.js';

const OPENAI_MODERATION_URL = 'https://api.openai.com/v1/moderations';
const MODEL = 'omni-moderation-latest';

/**
 * Check text content against the OpenAI Moderation API.
 * Fails open on API error (logs warning, allows content) to avoid false positives.
 * Skips check when OPENAI_API_KEY is absent or NODE_ENV is 'test'.
 */
export async function checkTextContent(text: string): Promise<void> {
  if (!process.env.OPENAI_API_KEY || process.env.NODE_ENV === 'test') {
    return;
  }

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 10_000);

  try {
    const response = await fetch(OPENAI_MODERATION_URL, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${process.env.OPENAI_API_KEY}`,
      },
      body: JSON.stringify({
        model: MODEL,
        input: text,
      }),
      signal: controller.signal,
    });

    if (!response.ok) {
      logger.warn('OpenAI Moderation API error (text), failing open', {
        status: response.status,
        statusText: response.statusText,
      });
      return;
    }

    const data = (await response.json()) as {
      results: Array<{ flagged: boolean }>;
    };

    if (data.results?.[0]?.flagged === true) {
      throw new ApplicationError(
        "We couldn't post this entry. Please review your text and try again.",
        422,
        'CONTENT_MODERATED'
      );
    }
  } catch (error) {
    if (error instanceof ApplicationError) {
      throw error;
    }
    logger.warn('OpenAI Moderation API request failed (text), failing open', {
      error: error instanceof Error ? error.message : String(error),
    });
  } finally {
    clearTimeout(timeout);
  }
}

/**
 * Check a photo (and optional text context) against the OpenAI Moderation API.
 * Uses multimodal input (image + optional log_title text).
 * Fails open on API error.
 * Skips check when OPENAI_API_KEY is absent or NODE_ENV is 'test'.
 */
export async function checkPhotoWithContext(
  imageBuffer: Buffer,
  logTitle?: string
): Promise<void> {
  if (!process.env.OPENAI_API_KEY || process.env.NODE_ENV === 'test') {
    return;
  }

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 10_000);

  try {
    const base64 = imageBuffer.toString('base64');
    const dataUrl = `data:image/jpeg;base64,${base64}`;

    type InputItem =
      | { type: 'text'; text: string }
      | { type: 'image_url'; image_url: { url: string } };

    const input: InputItem[] = [];
    if (logTitle) {
      input.push({ type: 'text', text: logTitle });
    }
    input.push({ type: 'image_url', image_url: { url: dataUrl } });

    const response = await fetch(OPENAI_MODERATION_URL, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${process.env.OPENAI_API_KEY}`,
      },
      body: JSON.stringify({
        model: MODEL,
        input,
      }),
      signal: controller.signal,
    });

    if (!response.ok) {
      logger.warn('OpenAI Moderation API error (photo), failing open', {
        status: response.status,
        statusText: response.statusText,
      });
      return;
    }

    const data = (await response.json()) as {
      results: Array<{ flagged: boolean }>;
    };

    if (data.results?.[0]?.flagged === true) {
      throw new ApplicationError(
        "This photo couldn't be uploaded. If you think this is a mistake, you can report it.",
        422,
        'CONTENT_MODERATED'
      );
    }
  } catch (error) {
    if (error instanceof ApplicationError) {
      throw error;
    }
    logger.warn('OpenAI Moderation API request failed (photo), failing open', {
      error: error instanceof Error ? error.message : String(error),
    });
  } finally {
    clearTimeout(timeout);
  }
}
