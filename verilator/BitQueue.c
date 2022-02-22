
#include "BitQueue.h"
#include <stdlib.h>
#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

#define ELEM_SIZE 64

// struct BitQueue {
//   // elements pop from head
//   // elems point from head to tail
//   struct BitQueueElem *head;
//   struct BitQueueElem *tail;
// 
//   uint8_t headSize;
//   uint8_t tailSize;
// };
// 
// struct BitQueueElem {
//   // head bit in lsb
//   uint64_t bits;
//   // points toward tail, or NULL if this is the tail
//   struct BitQueueElem *next;
// };

static char pushTailElem(struct BitQueue *queue) {
  struct BitQueueElem *newelem =
    (struct BitQueueElem*)malloc(sizeof(struct BitQueueElem));
  if(!newelem) return -1;
  newelem->bits = 0;
  newelem->next = NULL;
  
  if(queue->tail) {
    queue->tail->next = newelem;
  }
  else {
    queue->head = newelem;
  }
  queue->tail = newelem;
  queue->tailSize = 0;
  
  return 0;
}

static char pushHeadElem(struct BitQueue *queue) {
  struct BitQueueElem *newelem =
    (struct BitQueueElem*)malloc(sizeof(struct BitQueueElem));
  if(!newelem) return -1;
  newelem->bits = 0;
  newelem->next = queue->head;
  
  if(!queue->tail) {
    queue->tail = newelem;
    queue->tailSize = 0;
  }
  queue->head = newelem;
  queue->headSize = 0;
  
  return 0;
}

static char popElem(struct BitQueue *queue) {
  struct BitQueueElem *oldelem = queue->head;
  queue->head = queue->head->next;
  free(oldelem);
  if(!queue->head) {
    queue->tailSize = 0;
    queue->tail = NULL;
  }
  else if(queue->head == queue->tail) {
    queue->headSize = queue->tailSize;
  }
  else {
    queue->headSize = ELEM_SIZE;
  }
  
  return 0;
}

void bq_init(struct BitQueue *queue) {
  queue->head = queue->tail = NULL;
  queue->headSize = 0;
  queue->tailSize = 0;
}

char bq_pushTail(struct BitQueue *queue, bool bit) {
  if(queue->tailSize == ELEM_SIZE || queue->tail == NULL) {
    char err = pushTailElem(queue);
    if(err) return err;
  }
  
  queue->tail->bits |= (uint64_t)(!!bit) << queue->tailSize;
  queue->tailSize++;
  if(queue->tail == queue->head)
    queue->headSize++;
  
  return 0;
}

char bq_pushHead(struct BitQueue *queue, bool bit) {
  size_t prevsize = bq_size(queue);
  
  if(queue->headSize == ELEM_SIZE || queue->head == NULL) {
    char err = pushHeadElem(queue);
    if(err) return err;
  }
  
  queue->head->bits <<= 1;
  queue->head->bits |= !!bit;
  queue->headSize++;
  if(queue->head == queue->tail)
    queue->tailSize++;
  
  return 0;
}

char bq_pop(struct BitQueue *queue, bool *bit) {
  if(!queue->headSize) {
    return 1;
  }
  
  *bit = queue->head->bits & 1;
  queue->head->bits >>= 1;
  queue->headSize--;
  if(queue->head == queue->tail)
    queue->tailSize--;
  
  if(!queue->headSize) {
    popElem(queue);
  }
  
  return 0;
}

bool bq_isEmpty(struct BitQueue *queue) {
  return !queue->headSize;
}

size_t bq_size(struct BitQueue *queue) {
  if(queue->head == queue->tail) return queue->headSize;
  size_t size = queue->headSize + queue->tailSize;
  for(struct BitQueueElem *cur = queue->head; (cur = cur->next) != queue->tail;)
    size += ELEM_SIZE;
  return size;
}
